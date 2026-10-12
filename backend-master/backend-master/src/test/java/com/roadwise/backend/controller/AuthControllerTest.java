package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.PasswordResetToken;
import com.roadwise.backend.model.RefreshToken;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.SupabaseStorageService;
import com.roadwise.backend.service.RoadInspectionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AuthControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private SupabaseStorageService supabaseStorageService;

    @MockitoBean
    private RoadInspectionService roadInspectionService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setPassword(passwordEncoder.encode("Password123!"));
        testUser.setEmail("testuser@example.com");
        testUser.setRole("BARANGAY");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setStatus("Active");

        testUser = userRepository.save(testUser);
    }

    @Test
    void testLoginSuccess_MfaTriggeredWithoutDemoOtpInBody() throws Exception {
        String jsonPayload = "{\"username\":\"testuser\",\"password\":\"Password123!\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.demoOtp").doesNotExist());
    }

    @Test
    void testLoginFailure_InvalidCredentials() throws Exception {
        String jsonPayload = "{\"username\":\"testuser\",\"password\":\"WrongPassword\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password."));
    }

    @Test
    void testForgotPasswordRequest_DoesNotLeakUserId() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"testuser@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void testForgotPasswordRequest_UnknownEmail_IdenticalResponse() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void testForgotPasswordReset_AttemptsAreCapped() throws Exception {
        passwordResetTokenRepository.save(new PasswordResetToken(
                testUser, passwordEncoder.encode("123456"), LocalDateTime.now().plusMinutes(10)));

        String wrongOtpBody = "{\"email\":\"testuser@example.com\",\"otp\":\"000000\",\"newPassword\":\"NewPassword1!\"}";
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/forgot-password/reset")
                            .contentType(MediaType.APPLICATION_JSON).content(wrongOtpBody))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/forgot-password/reset")
                        .contentType(MediaType.APPLICATION_JSON).content(wrongOtpBody))
                .andExpect(status().isTooManyRequests());

        // The exhausted code cannot be reused, even with the correct OTP.
        mockMvc.perform(post("/api/auth/forgot-password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"testuser@example.com\",\"otp\":\"123456\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testForgotPasswordReset_RejectsWeakPassword() throws Exception {
        passwordResetTokenRepository.save(new PasswordResetToken(
                testUser, passwordEncoder.encode("123456"), LocalDateTime.now().plusMinutes(10)));

        mockMvc.perform(post("/api/auth/forgot-password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"testuser@example.com\",\"otp\":\"123456\",\"newPassword\":\"weakpass\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testRefreshToken_ReuseRevokesWholeFamily() throws Exception {
        String rawToken = "raw-refresh-token-1";
        String hash = AuthController.hashToken(rawToken);
        refreshTokenRepository.save(new RefreshToken(testUser, hash, LocalDateTime.now().plusDays(7), "127.0.0.1"));

        String firstResponse = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String rotatedToken = JsonPath.read(firstResponse, "$.refreshToken");

        // Replay of the rotated token is detected...
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // ...and the whole family (including the legitimate successor) is revoked.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotatedToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testLogout_RevokesExistingAccessToken() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(testUser);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/reports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}

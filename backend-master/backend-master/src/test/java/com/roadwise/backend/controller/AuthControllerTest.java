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
import com.roadwise.backend.service.TurnstileService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    /**
     * Cloudflare verification is stubbed here. What this class covers is "password rules and
     * lockouts apply once the bot check passes"; TurnstileService's own behaviour is tested
     * against its own stub, so no test in this suite reaches challenges.cloudflare.com.
     */
    @MockitoBean
    private TurnstileService turnstileService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Default verdict: the bot check passes, so these tests exercise the credential path.
        // any() rather than anyString() because a body with no turnstileToken arrives as null.
        when(turnstileService.verify(any(), any()))
                .thenReturn(new TurnstileService.Result(true, null));

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
    void testLoginBlocked_WhenTurnstileVerificationFails() throws Exception {
        when(turnstileService.verify(any(), any()))
                .thenReturn(new TurnstileService.Result(false, "invalid-input-response"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"Password123!\","
                                + "\"turnstileToken\":\"forged-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("TURNSTILE_FAILED"))
                .andExpect(jsonPath("$.mfaRequired").doesNotExist());

        // A refused bot check is not a credential attempt: the *correct* password presented
        // with a rejected token must leave the account's lockout state untouched, so that
        // sprayed junk tokens cannot be used to lock a known account out.
        assertEquals(0, userRepository.findById(testUser.getId()).orElseThrow()
                .getFailedLoginAttempts());
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

    @Test
    void testPasswordChange_RevokesSessionsAndRefreshTokens() throws Exception {
        String rawToken = "refresh-token-before-password-change";
        refreshTokenRepository.save(new RefreshToken(testUser, AuthController.hashToken(rawToken),
                LocalDateTime.now().plusDays(7), "127.0.0.1"));
        String accessToken = jwtTokenProvider.generateAccessToken(testUser);

        mockMvc.perform(put("/api/users/" + testUser.getId() + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password123!\",\"newPassword\":\"NewPassw0rd!\"}")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // A refresh token minted before the change can no longer rotate.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // The access token issued before the change is rejected immediately.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/users/" + testUser.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testProfileUpdate_CannotChangeEmail() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(testUser);

        mockMvc.perform(put("/api/users/" + testUser.getId() + "/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"attacker@example.com\",\"phoneNumber\":\"0999999999\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals("testuser@example.com", reloaded.getEmail());
        assertEquals("0999999999", reloaded.getPhoneNumber());
    }

    @Test
    void testEmailChange_RequiresVerifiedOtpAndRevokesSessions() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(testUser);

        // Wrong password is rejected.
        mockMvc.perform(post("/api/users/" + testUser.getId() + "/email-change/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"nope\",\"email\":\"new.address@example.com\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // The correct password only queues the change.
        mockMvc.perform(post("/api/users/" + testUser.getId() + "/email-change/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password123!\",\"email\":\"new.address@example.com\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User pending = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals("testuser@example.com", pending.getEmail());
        assertEquals("new.address@example.com", pending.getPendingEmail());

        // Pull the OTP out of the mocked email sent to the NEW address.
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce())
                .sendEmail(eq("new.address@example.com"), anyString(), bodyCaptor.capture());
        Matcher matcher = Pattern.compile("verification code is: (\\d{6})")
                .matcher(bodyCaptor.getValue());
        assertTrue(matcher.find(), "OTP should be embedded in the new-address email");
        String otp = matcher.group(1);

        // A wrong OTP does not activate the address.
        mockMvc.perform(post("/api/users/" + testUser.getId() + "/email-change/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"000000\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertEquals("testuser@example.com",
                userRepository.findById(testUser.getId()).orElseThrow().getEmail());

        // The real OTP activates the address and revokes the pre-change session.
        mockMvc.perform(post("/api/users/" + testUser.getId() + "/email-change/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"" + otp + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User confirmed = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals("new.address@example.com", confirmed.getEmail());
        assertNull(confirmed.getPendingEmail());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/users/" + testUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}

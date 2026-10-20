package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
class AuthLoginEnumerationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LoginRateLimitRepository loginRateLimitRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private MfaChallengeRepository mfaChallengeRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private EmailService emailService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private SupabaseStorageService supabaseStorageService;
    @MockitoBean private RoadInspectionService roadInspectionService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        seedUser("testuser", "Password123!");
    }

    private User seedUser(String username, String rawPassword) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setEmail(username + "@example.com");
        u.setRole("BARANGAY");
        u.setFirstName("Test");
        u.setLastName("User");
        u.setStatus("Active");
        return userRepository.save(u);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() throws Exception {
        MvcResult unknown = login("nosuchuser", "Password123!");
        MvcResult wrong = login("testuser", "WrongPassword!");

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(401, wrong.getResponse().getStatus());
        assertEquals(wrong.getResponse().getContentAsString(),
                unknown.getResponse().getContentAsString(),
                "the two failure responses must be byte-identical");
    }

    @Test
    void repeatedFailuresThrottleButNeverLockTheAccount() throws Exception {
        boolean throttled = false;
        for (int attempt = 0; attempt < 12 && !throttled; attempt++) {
            throttled = login("testuser", "WrongPassword!").getResponse().getStatus() == 429;
        }
        assertTrue(throttled, "repeated failures must eventually be throttled");

        loginRateLimitRepository.deleteAll();

        assertEquals(200, login("testuser", "Password123!").getResponse().getStatus(),
                "clearing the limiter must be sufficient to log in; no account lockout may remain");
    }

    @Test
    void expiredTemporaryPasswordIsOnlyReportedWithTheCorrectPassword() throws Exception {
        User expired = seedUser("tempuser", "Password123!");
        expired.setPasswordChangeRequired(true);
        expired.setTempPasswordExpiresAt(LocalDateTime.now().minusHours(1));
        userRepository.save(expired);

        MvcResult wrong = login("tempuser", "WrongPassword!");
        assertEquals(401, wrong.getResponse().getStatus());
        assertFalse(wrong.getResponse().getContentAsString().contains("Temporary password has expired"),
                "the expiry message must not be an unauthenticated account-state oracle");

        MvcResult right = login("tempuser", "Password123!");
        assertEquals(401, right.getResponse().getStatus());
        assertTrue(right.getResponse().getContentAsString().contains("Temporary password has expired"),
                "with the correct password the expiry message is appropriate");
    }
}

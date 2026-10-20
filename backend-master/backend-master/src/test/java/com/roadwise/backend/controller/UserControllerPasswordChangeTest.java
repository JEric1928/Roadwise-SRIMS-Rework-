package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class UserControllerPasswordChangeTest {

    private static final String WRONG_BODY =
            "{\"currentPassword\":\"WrongPassword!\",\"newPassword\":\"NewPassword123!\"}";

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LoginRateLimitRepository loginRateLimitRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private MfaChallengeRepository mfaChallengeRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private EmailService emailService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private SupabaseStorageService supabaseStorageService;
    @MockitoBean private RoadInspectionService roadInspectionService;

    private User target;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        target = seedUser("lockouttarget", "BARANGAY");
    }

    private User seedUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("Password123!"));
        u.setEmail(username + "@example.com");
        u.setRole(role);
        u.setFirstName("Test");
        u.setLastName("User");
        u.setStatus("Active");
        return userRepository.save(u);
    }

    private MvcResult attempt(Long id, String token) throws Exception {
        return mockMvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WRONG_BODY))
                .andReturn();
    }

    private void assertEventuallyThrottled(Long id, String token) throws Exception {
        boolean throttled = false;
        for (int attempt = 0; attempt < 12 && !throttled; attempt++) {
            throttled = attempt(id, token).getResponse().getStatus() == 429;
        }
        assertTrue(throttled, "repeated wrong current passwords must eventually be rate limited");
    }

    private void assertLoginStillWorks(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Password123!\"}"))
                .andReturn();
        assertEquals(200, login.getResponse().getStatus(),
                "the account must remain able to log in — it was never locked out");
    }

    @Test
    void wrongCurrentPasswordThrottlesWithoutTouchingTheLoginLockout() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(target);

        for (int attempt = 1; attempt <= 6; attempt++) {
            mockMvc.perform(put("/api/users/" + target.getId() + "/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(WRONG_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Incorrect current password."));
        }

        assertEventuallyThrottled(target.getId(), token);
        assertLoginStillWorks("lockouttarget");
    }

    @Test
    void adminCannotLockAnotherAccountOutOfLogin() throws Exception {
        User admin = seedUser("lockoutadmin", "ADMIN");
        String adminToken = jwtTokenProvider.generateAccessToken(admin);

        assertEventuallyThrottled(target.getId(), adminToken);
        assertLoginStillWorks("lockouttarget");
    }
}

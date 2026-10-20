package com.roadwise.backend.config;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class ActuatorAuthorizationTest {

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

    private User barangayUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        barangayUser = new User();
        barangayUser.setUsername("brgyuser");
        barangayUser.setPassword(passwordEncoder.encode("Password123!"));
        barangayUser.setRole("BARANGAY");
        barangayUser.setEmail("brgy@example.com");
        barangayUser.setStatus("Active");
        barangayUser = userRepository.save(barangayUser);

        adminUser = new User();
        adminUser.setUsername("adminuser");
        adminUser.setPassword(passwordEncoder.encode("Password123!"));
        adminUser.setRole("ADMIN");
        adminUser.setEmail("admin@example.com");
        adminUser.setStatus("Active");
        adminUser = userRepository.save(adminUser);
    }

    @Test
    void healthEndpointIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void metricsEndpointIsForbiddenForNonAdminUser() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(barangayUser);

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsEndpointIsAccessibleForAdminUser() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(adminUser);

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}

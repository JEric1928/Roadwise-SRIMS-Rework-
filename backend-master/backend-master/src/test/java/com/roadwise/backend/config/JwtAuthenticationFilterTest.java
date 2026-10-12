package com.roadwise.backend.config;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.SupabaseStorageService;
import com.roadwise.backend.service.RoadInspectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class JwtAuthenticationFilterTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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

    private User suspendedUser;
    private User deactivatedUser;
    private User activeUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        userRepository.deleteAll();

        suspendedUser = new User();
        suspendedUser.setUsername("suspendeduser");
        suspendedUser.setPassword("Password123!");
        suspendedUser.setEmail("suspended@example.com");
        suspendedUser.setRole("BARANGAY");
        suspendedUser.setStatus("Suspended");
        userRepository.save(suspendedUser);

        deactivatedUser = new User();
        deactivatedUser.setUsername("deactivateduser");
        deactivatedUser.setPassword("Password123!");
        deactivatedUser.setEmail("deactivated@example.com");
        deactivatedUser.setRole("BARANGAY");
        deactivatedUser.setStatus("Deactivated");
        userRepository.save(deactivatedUser);

        activeUser = new User();
        activeUser.setUsername("activeuser");
        activeUser.setPassword("Password123!");
        activeUser.setEmail("active@example.com");
        activeUser.setRole("BARANGAY");
        activeUser.setStatus("Active");
        userRepository.save(activeUser);
    }

    @Test
    void testSuspendedUserWithValidToken_Forbidden() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(suspendedUser);

        mockMvc.perform(get("/api/reports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDeactivatedUserWithValidToken_Forbidden() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(deactivatedUser);

        mockMvc.perform(get("/api/reports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void testActiveUserWithValidToken_Allowed() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(activeUser);

        mockMvc.perform(get("/api/reports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}

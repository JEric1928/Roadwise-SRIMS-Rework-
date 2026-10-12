package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class SystemSettingsControllerTest {

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

    private String barangayToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        userRepository.deleteAll();

        User barangayUser = new User();
        barangayUser.setUsername("brgyuser");
        barangayUser.setPassword("Password123!");
        barangayUser.setEmail("brgy@example.com");
        barangayUser.setRole("BARANGAY");
        barangayUser.setStatus("Active");
        userRepository.save(barangayUser);
        barangayToken = jwtTokenProvider.generateAccessToken(barangayUser);

        User adminUser = new User();
        adminUser.setUsername("adminuser");
        adminUser.setPassword("Password123!");
        adminUser.setEmail("admin@example.com");
        adminUser.setRole("ADMIN");
        adminUser.setStatus("Active");
        userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);
    }

    @Test
    void testToggleMaintenance_BarangayUserDenied() throws Exception {
        mockMvc.perform(post("/api/settings/toggle-maintenance")
                        .header("Authorization", "Bearer " + barangayToken)
                        .param("status", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testToggleMaintenance_AdminUserAllowed() throws Exception {
        mockMvc.perform(post("/api/settings/toggle-maintenance")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "true"))
                .andExpect(status().isOk());
    }
}

package com.roadwise.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.ActivityLog;
import com.roadwise.backend.model.Notification;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.ActivityLogRepository;
import com.roadwise.backend.repository.NotificationRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private EmailService emailService;
    @MockitoBean
    private SupabaseStorageService supabaseStorageService;
    @MockitoBean
    private RoadInspectionService roadInspectionService;

    private User userA;
    private User userB;
    private String tokenA;

    @BeforeEach
    void setUp() {
        activityLogRepository.deleteAll();
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        userA = new User();
        userA.setUsername("user-a");
        userA.setPassword(passwordEncoder.encode("OldPassword123!"));
        userA.setRole("BARANGAY");
        userA.setFirstName("User");
        userA.setLastName("A");
        userA.setStatus("Active");
        userA.setEmail("a@example.com");
        userA = userRepository.save(userA);

        userB = new User();
        userB.setUsername("user-b");
        userB.setPassword(passwordEncoder.encode("AnotherPass123!"));
        userB.setRole("BARANGAY");
        userB.setFirstName("User");
        userB.setLastName("B");
        userB.setStatus("Active");
        userB.setEmail("b@example.com");
        userB = userRepository.save(userB);

        tokenA = jwtTokenProvider.generateAccessToken(userA);
    }

    @Test
    void roadsGetRouteIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/roads"))
                .andExpect(status().isOk());
    }

    @Test
    void notificationsMeAndOwnershipEnforced() throws Exception {
        Notification mine = new Notification();
        mine.setRecipient(userA);
        mine.setTitle("Mine");
        mine.setMessage("owned by A");
        notificationRepository.save(mine);

        Notification others = new Notification();
        others.setRecipient(userB);
        others.setTitle("Other");
        others.setMessage("owned by B");
        others = notificationRepository.save(others);

        mockMvc.perform(get("/api/notifications/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Mine"));

        mockMvc.perform(put("/api/notifications/" + others.getId() + "/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void activityLogAttributionUsesAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(post("/api/activity-logs/log-action")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", userB.getId(),
                                "category", "EXPORT",
                                "action", "EXPORT_CSV",
                                "targetEntity", "SYSTEM"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(userA.getId()));

        ActivityLog latest = activityLogRepository.findAllByOrderByTimestampDesc().get(0);
        assertThat(latest.getActorId()).isEqualTo(userA.getId());
    }

    @Test
    void userPasswordChangeUsesHashing() throws Exception {
        mockMvc.perform(put("/api/users/" + userA.getId() + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "OldPassword123!",
                                "newPassword", "NewPassword456!"
                        ))))
                .andExpect(status().isOk());

        User updated = userRepository.findById(userA.getId()).orElseThrow();
        assertThat(updated.getPassword()).isNotEqualTo("NewPassword456!");
        assertThat(passwordEncoder.matches("NewPassword456!", updated.getPassword())).isTrue();
    }
}

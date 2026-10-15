package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit attribution on the user-management surface.
 *
 * <p>Two endpoints used to resolve the audit actor from a client-supplied
 * {@code adminId} in the request body, with a fallback that picked an arbitrary
 * administrator (first row of {@code findAll()}, else user id 1). An authorised
 * admin could therefore have an action recorded against a different account,
 * and the fallback attributed actions to whoever happened to sort first. {@link
 * com.roadwise.backend.util.SecurityUtils} states the rule plainly: controllers
 * must never trust a client-supplied id for identity or audit purposes.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAuditAttributionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private SupabaseStorageService supabaseStorageService;

    private MockMvc mockMvc;
    private User actingAdmin;
    private User otherAdmin;
    private User target;
    private String actingAdminToken;

    private void setUpData() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        refreshTokenRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        loginRateLimitRepository.deleteAll();
        userRepository.deleteAll();
        barangayRepository.deleteAll();

        Barangay barangay = new Barangay();
        barangay.setBarangayName("Barangay One");
        barangay = barangayRepository.save(barangay);

        actingAdmin = saveUser("acting.admin", "ADMIN", null);
        otherAdmin = saveUser("other.admin", "CPDO Admin", null);
        target = saveUser("brgy.official", "BARANGAY", barangay);

        actingAdminToken = jwtTokenProvider.generateAccessToken(actingAdmin);
    }

    private User saveUser(String username, String role, Barangay barangay) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("Password123!");
        user.setEmail(username + "@example.com");
        user.setRole(role);
        user.setFirstName(username);
        user.setLastName("User");
        user.setStatus("Active");
        user.setBarangay(barangay);
        return userRepository.save(user);
    }

    /** The actor recorded must be the caller, not the adminId supplied in the body. */
    @Test
    void manageUser_attributesTheActionToTheAuthenticatedAdmin() throws Exception {
        setUpData();

        mockMvc.perform(put("/api/users/" + target.getId() + "/manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Renamed\",\"adminId\":\"" + otherAdmin.getId() + "\"}")
                        .header("Authorization", "Bearer " + actingAdminToken))
                .andExpect(status().isOk());

        ArgumentCaptor<User> actor = ArgumentCaptor.forClass(User.class);
        verify(activityLogService).log(
                actor.capture(),
                eq("USER"),
                eq("USER_RECORD_MANAGED"),
                eq("#USR-" + target.getId()),
                anyString(),
                eq("SUCCESS"),
                any());

        assertEquals(actingAdmin.getId(), actor.getValue().getId(),
                "the audit actor must be the authenticated caller");
        assertNotEquals(otherAdmin.getId(), actor.getValue().getId(),
                "a client-supplied adminId must not be able to reassign the action");
    }

    /** Forged attribution must not survive even when no adminId is supplied. */
    @Test
    void manageUser_neverFallsBackToAnArbitraryAdministrator() throws Exception {
        setUpData();

        mockMvc.perform(put("/api/users/" + target.getId() + "/manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Changed\"}")
                        .header("Authorization", "Bearer " + actingAdminToken))
                .andExpect(status().isOk());

        ArgumentCaptor<User> actor = ArgumentCaptor.forClass(User.class);
        verify(activityLogService).log(
                actor.capture(),
                eq("USER"),
                eq("USER_RECORD_MANAGED"),
                anyString(),
                anyString(),
                eq("SUCCESS"),
                any());

        assertEquals(actingAdmin.getId(), actor.getValue().getId());
    }

    /** The endpoint still performs its actual job. */
    @Test
    void manageUser_stillUpdatesTheTargetRecord() throws Exception {
        setUpData();

        mockMvc.perform(put("/api/users/" + target.getId() + "/manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Renamed\"}")
                        .header("Authorization", "Bearer " + actingAdminToken))
                .andExpect(status().isOk());

        User updated = userRepository.findById(target.getId()).orElseThrow();
        assertEquals("Renamed", updated.getFirstName());
    }

    /** A non-admin must not reach the endpoint at all. */
    @Test
    void manageUser_rejectsNonAdministrators() throws Exception {
        setUpData();
        String barangayToken = jwtTokenProvider.generateAccessToken(target);

        mockMvc.perform(put("/api/users/" + target.getId() + "/manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Nope\"}")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isForbidden());
    }

}

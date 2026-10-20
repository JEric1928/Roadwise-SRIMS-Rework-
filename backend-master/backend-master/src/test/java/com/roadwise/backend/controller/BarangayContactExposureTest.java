package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.RoadReportRepository;
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class BarangayContactExposureTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private BarangayRepository barangayRepository;
    @Autowired private CityRoadRepository cityRoadRepository;
    @Autowired private RoadReportRepository roadReportRepository;
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

    private Barangay barangay;
    private User official;
    private User barangayUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        roadReportRepository.deleteAll();
        cityRoadRepository.deleteAll();
        userRepository.deleteAll();
        barangayRepository.deleteAll();

        barangay = new Barangay();
        barangay.setBarangayName("Muzon");
        barangay = barangayRepository.save(barangay);

        official = new User();
        official.setUsername("official1");
        official.setPassword(passwordEncoder.encode("Password123!"));
        official.setRole("BARANGAY");
        official.setFirstName("Juan");
        official.setLastName("Dela Cruz");
        official.setEmail("juan@example.com");
        official.setPhoneNumber("09123456789");
        official.setBarangay(barangay);
        official.setStatus("Active");
        userRepository.save(official);

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
    void nonAdminUserSeesNullContactInfo() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(barangayUser);

        mockMvc.perform(get("/api/barangays/" + barangay.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(barangay.getId().intValue())))
                .andExpect(jsonPath("$.barangayName", is("Muzon")))
                .andExpect(jsonPath("$.brgyCaptain", is(nullValue())))
                .andExpect(jsonPath("$.contactNumber", is(nullValue())))
                .andExpect(jsonPath("$.emailAddress", is(nullValue())));
    }

    @Test
    void adminUserSeesOfficialContactInfo() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(adminUser);

        mockMvc.perform(get("/api/barangays/" + barangay.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(barangay.getId().intValue())))
                .andExpect(jsonPath("$.barangayName", is("Muzon")))
                .andExpect(jsonPath("$.brgyCaptain", is("Juan Dela Cruz")))
                .andExpect(jsonPath("$.contactNumber", is("09123456789")))
                .andExpect(jsonPath("$.emailAddress", is("juan@example.com")));
    }
}

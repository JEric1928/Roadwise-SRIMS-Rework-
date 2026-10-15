package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.ReportStatusLogRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class RoadReportWorkflowTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RoadReportRepository reportRepository;

    @Autowired
    private CityRoadRepository cityRoadRepository;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReportStatusLogRepository reportStatusLogRepository;

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

    private User adminUser;
    private User engineerUser;
    private User barangayUser;
    private Barangay testBarangay;
    private CityRoad testRoad;

    private String adminToken;
    private String engineerToken;
    private String barangayToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        reportRepository.deleteAll();
        cityRoadRepository.deleteAll();
        userRepository.deleteAll();
        barangayRepository.deleteAll();

        testBarangay = new Barangay();
        testBarangay.setBarangayName("Barangay One");
        testBarangay = barangayRepository.save(testBarangay);

        testRoad = new CityRoad();
        testRoad.setRoadId("RD-101");
        testRoad.setRoadName("Main Street");
        testRoad.setRoadImportance("Core");
        testRoad.setRoadType("Concrete");
        testRoad.setTerrainType("Flat");
        testRoad.setBarangay(testBarangay);
        testRoad = cityRoadRepository.save(testRoad);

        barangayUser = new User();
        barangayUser.setUsername("brgyuser");
        barangayUser.setPassword("Password123!");
        barangayUser.setEmail("brgy@example.com");
        barangayUser.setRole("BARANGAY");
        barangayUser.setFirstName("Barangay");
        barangayUser.setLastName("Official");
        barangayUser.setBarangay(testBarangay);
        barangayUser.setStatus("Active");
        userRepository.save(barangayUser);
        barangayToken = jwtTokenProvider.generateAccessToken(barangayUser);

        adminUser = new User();
        adminUser.setUsername("adminuser");
        adminUser.setPassword("Password123!");
        adminUser.setEmail("admin@example.com");
        adminUser.setRole("ADMIN");
        adminUser.setFirstName("CPDO");
        adminUser.setLastName("Admin");
        adminUser.setStatus("Active");
        userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);

        engineerUser = new User();
        engineerUser.setUsername("engineeruser");
        engineerUser.setPassword("Password123!");
        engineerUser.setEmail("engineer@example.com");
        engineerUser.setRole("ENGINEER");
        engineerUser.setFirstName("City");
        engineerUser.setLastName("Engineer");
        engineerUser.setStatus("Active");
        userRepository.save(engineerUser);
        engineerToken = jwtTokenProvider.generateAccessToken(engineerUser);

        when(roadInspectionService.analyzeSeverity(any())).thenReturn(
                RoadInspectionService.InspectionResult.unassessed("AI_UNAVAILABLE", "AI service call failed.")
        );
        when(supabaseStorageService.uploadImageQuarantined(any())).thenReturn("quarantine/sample.jpg");
        when(supabaseStorageService.uploadImage(any())).thenReturn("reports/sample.jpg");
    }

    @Test
    void testCreateReport_PopulatesCityRoadSnapshots() throws Exception {
        MockMultipartFile imageFile = new MockMultipartFile("imageFile", "test.jpg", "image/jpeg", "image content".getBytes());

        mockMvc.perform(multipart("/api/reports")
                        .file(imageFile)
                        .param("damageDescription", "Pothole detected")
                        .param("cityRoadId", testRoad.getId().toString())
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk());

        List<RoadReport> reports = reportRepository.findAll();
        assertEquals(1, reports.size());
        RoadReport saved = reports.get(0);
        assertEquals("RD-101", saved.getCityRoadId());
        assertEquals(testRoad.getId(), saved.getCityRoadFkId());
        assertEquals("Main Street", saved.getCityRoadName());
        assertEquals("Core", saved.getRoadImportance());
        assertEquals("Concrete", saved.getRoadType());
        assertEquals("Flat", saved.getTerrainType());
    }

    @Test
    void testCreateReport_ResolvesHumanReadableRoadCode() throws Exception {
        MockMultipartFile imageFile = new MockMultipartFile("imageFile", "test.jpg", "image/jpeg", "image content".getBytes());

        mockMvc.perform(multipart("/api/reports")
                        .file(imageFile)
                        .param("damageDescription", "Code lookup")
                        .param("cityRoadId", "RD-101")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk());

        RoadReport saved = reportRepository.findAll().get(0);
        assertEquals(testRoad.getId(), saved.getCityRoadFkId());
        assertEquals("RD-101", saved.getCityRoadId());
    }

    @Test
    void testCreateReport_PersistsDuplicateFlag() throws Exception {
        MockMultipartFile imageFile = new MockMultipartFile("imageFile", "test.jpg", "image/jpeg", "image content".getBytes());

        mockMvc.perform(multipart("/api/reports")
                        .file(imageFile)
                        .param("damageDescription", "First pothole")
                        .param("latitude", "14.0")
                        .param("longitude", "121.0")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk());

        RoadReport first = reportRepository.findAll().get(0);

        mockMvc.perform(multipart("/api/reports")
                        .file(imageFile)
                        .param("damageDescription", "Second pothole")
                        .param("latitude", "14.0001")
                        .param("longitude", "121.0001")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk());

        List<RoadReport> reports = reportRepository.findAll();
        assertEquals(2, reports.size());
        RoadReport second = reports.stream()
                .filter(r -> !r.getId().equals(first.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, second.getPotentialDuplicate());
        assertEquals(first.getId(), second.getDuplicateOfReportId());
    }

    @Test
    void testEditTerminalReport_Rejected() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Closed Road");
        report.setStatus("Completed");
        report.setProofOfRepairImage("proof/sample.jpg");
        report = reportRepository.save(report);

        mockMvc.perform(put("/api/reports/update/" + report.getId())
                        .param("damageDescription", "Late edit attempt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void testResolveWithoutProof_Rejected() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Unproven Road");
        report.setStatus("In Progress");
        report = reportRepository.save(report);

        mockMvc.perform(put("/api/reports/" + report.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Resolved\"}")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testReminderWithoutEngineer_ReturnsConflict() throws Exception {
        userRepository.deleteById(engineerUser.getId());

        RoadReport report = new RoadReport();
        report.setCityRoadName("No Engineer Road");
        report.setStatus("In Progress");
        report.setTargetCompletionDate(LocalDate.now().plusDays(5));
        report = reportRepository.save(report);

        mockMvc.perform(post("/api/reports/" + report.getId() + "/remind-due")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        RoadReport unchanged = reportRepository.findById(report.getId()).orElseThrow();
        assertNull(unchanged.getLastReminderSent());
    }

    @Test
    void testAnnualRollover_ArchivesWithTimelineAndVersion() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Rollover Road");
        report.setStatus("Pending Validation");
        report = reportRepository.save(report);
        Long versionBefore = report.getVersion();

        mockMvc.perform(post("/api/reports/rollover-annual-cycle")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        RoadReport archived = reportRepository.findById(report.getId()).orElseThrow();
        assertEquals("Archived", archived.getStatus());
        assertNotNull(archived.getDateArchived());
        assertNotNull(versionBefore);
        assertTrue(archived.getVersion() > versionBefore, "@Version must increment on rollover");
        assertTrue(reportStatusLogRepository.findByReport_IdOrderByCreatedAtAsc(report.getId()).stream()
                        .anyMatch(log -> "ARCHIVED".equals(log.getAction())),
                "Annual rollover must write a timeline entry");
    }

    @Test
    void testBatchDefer_EnforcesTransitionRule() throws Exception {
        RoadReport r1 = new RoadReport();
        r1.setCityRoadName("Road A");
        r1.setStatus("Dispatched to CEO");
        r1 = reportRepository.save(r1);

        RoadReport r2 = new RoadReport();
        r2.setCityRoadName("Road B");
        r2.setStatus("Pending Validation");
        r2 = reportRepository.save(r2);

        String jsonPayload = String.format("{\"repairRemarks\":\"Lack of budget\",\"reportIds\":[%d]}", r1.getId());

        mockMvc.perform(post("/api/reports/batch/defer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk());

        RoadReport updatedR1 = reportRepository.findById(r1.getId()).orElseThrow();
        assertEquals("Pending Budget", updatedR1.getStatus());

        String invalidBatchPayload = String.format("{\"repairRemarks\":\"Lack of budget\",\"reportIds\":[%d]}", r2.getId());
        mockMvc.perform(post("/api/reports/batch/defer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBatchPayload)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCompleteReport_RequiresProofImage() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Road Test");
        report.setStatus("In Progress");
        report = reportRepository.save(report);

        mockMvc.perform(multipart("/api/reports/" + report.getId() + "/complete")
                        .param("repairRemarks", "Finished work")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isBadRequest());

        MockMultipartFile proofFile = new MockMultipartFile("proofImage", "proof.jpg", "image/jpeg", "proof content".getBytes());

        mockMvc.perform(multipart("/api/reports/" + report.getId() + "/complete")
                        .file(proofFile)
                        .param("repairRemarks", "Finished work")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk());

        RoadReport updated = reportRepository.findById(report.getId()).orElseThrow();
        assertEquals("Completed", updated.getStatus());
        assertNotNull(updated.getProofOfRepairImage());
    }

    @Test
    void testSendDeadlineReminder_NotifiesEngineer() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Expressway 1");
        report.setStatus("In Progress");
        report.setTargetCompletionDate(LocalDate.now().plusDays(5));
        report = reportRepository.save(report);

        mockMvc.perform(post("/api/reports/" + report.getId() + "/remind-due")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(notificationService).sendNotification(
                eq(engineerUser.getId()),
                contains("Deadline Expedite Reminder"),
                contains("Expressway 1"),
                eq("REMINDER")
        );
    }

    @Test
    void testStatusChange_EngineerCannotDispatchOrSetDeadlines() throws Exception {
        RoadReport report = new RoadReport();
        report.setCityRoadName("Role Matrix Road");
        report.setStatus("Validated");
        report = reportRepository.save(report);

        // Dispatch is an administrative transition.
        mockMvc.perform(put("/api/reports/" + report.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Dispatched to CEO\"}")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());

        RoadReport unchanged = reportRepository.findById(report.getId()).orElseThrow();
        assertEquals("Validated", unchanged.getStatus());

        // Deadline edits belong to administrative scheduling, not the repair flow.
        RoadReport dispatchable = new RoadReport();
        dispatchable.setCityRoadName("Deadline Road");
        dispatchable.setStatus("Dispatched to CEO");
        dispatchable = reportRepository.save(dispatchable);

        mockMvc.perform(put("/api/reports/" + dispatchable.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"In Progress\",\"targetCompletionDate\":\"2030-01-01\"}")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());
        assertNull(reportRepository.findById(dispatchable.getId()).orElseThrow().getTargetCompletionDate());

        // ...while administrators may dispatch.
        mockMvc.perform(put("/api/reports/" + report.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Dispatched to CEO\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertEquals("Dispatched to CEO",
                reportRepository.findById(report.getId()).orElseThrow().getStatus());
    }

    @Test
    void testTimeline_EnforcesBarangayOwnership() throws Exception {
        Barangay otherBarangay = new Barangay();
        otherBarangay.setBarangayName("Other Barangay");
        otherBarangay = barangayRepository.save(otherBarangay);

        RoadReport foreign = new RoadReport();
        foreign.setCityRoadName("Foreign Road");
        foreign.setStatus("Pending Validation");
        foreign.setBarangay(otherBarangay);
        foreign = reportRepository.save(foreign);

        RoadReport own = new RoadReport();
        own.setCityRoadName("Own Road");
        own.setStatus("Pending Validation");
        own.setBarangay(testBarangay);
        own = reportRepository.save(own);

        mockMvc.perform(get("/api/reports/" + foreign.getId() + "/timeline")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/" + own.getId() + "/timeline")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk());
    }

    /**
     * The report list caps a Barangay Official to their own barangay, so the
     * dashboard totals must be capped the same way — otherwise the endpoint is a
     * way to read city-wide volumes the caller cannot list. Administrators keep
     * the unscoped view.
     */
    @Test
    void testDashboardStats_AreScopedByRole() throws Exception {
        Barangay otherBarangay = new Barangay();
        otherBarangay.setBarangayName("Other Barangay");
        otherBarangay = barangayRepository.save(otherBarangay);

        RoadReport mine = new RoadReport();
        mine.setCityRoadName("Mine");
        mine.setSeverity("High");
        mine.setStatus("Pending Validation");
        mine.setBarangay(testBarangay);
        reportRepository.save(mine);

        RoadReport theirs = new RoadReport();
        theirs.setCityRoadName("Theirs");
        theirs.setSeverity("Low");
        theirs.setStatus("Pending Validation");
        theirs.setBarangay(otherBarangay);
        reportRepository.save(theirs);

        // A Barangay Official sees only their own barangay's totals.
        String barangayBody = mockMvc.perform(get("/api/reports/dashboard-stats")
                        .header("Authorization", "Bearer " + barangayToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(barangayBody.contains("\"totalReports\":1"),
                "official must not see city-wide totals: " + barangayBody);
        assertTrue(barangayBody.contains("\"scope\":\"barangay\""), barangayBody);
        assertFalse(barangayBody.contains("\"Low\""),
                "another barangay's severities must not be counted: " + barangayBody);

        // An administrator sees everything.
        String adminBody = mockMvc.perform(get("/api/reports/dashboard-stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(adminBody.contains("\"totalReports\":2"), adminBody);
        assertTrue(adminBody.contains("\"scope\":\"city-wide\""), adminBody);

        // The two scopes must never share a cache entry.
        assertNotEquals(barangayBody, adminBody);
    }

    @Test
    void testDashboardStats_UnassignedOfficialIsDenied() throws Exception {
        User unassigned = new User();
        unassigned.setUsername("unassignedbrgy");
        unassigned.setPassword("Password123!");
        unassigned.setEmail("unassigned@example.com");
        unassigned.setRole("BARANGAY");
        unassigned.setFirstName("Unassigned");
        unassigned.setLastName("Official");
        unassigned.setStatus("Active");
        userRepository.save(unassigned);
        String token = jwtTokenProvider.generateAccessToken(unassigned);

        mockMvc.perform(get("/api/reports/dashboard-stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}

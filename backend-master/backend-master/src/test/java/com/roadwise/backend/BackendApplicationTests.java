package com.roadwise.backend;

import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

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

	@Test
	void contextLoads() {
	}

}

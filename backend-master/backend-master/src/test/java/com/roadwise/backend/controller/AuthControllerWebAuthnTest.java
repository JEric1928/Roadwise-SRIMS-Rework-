package com.roadwise.backend.controller;

import com.roadwise.backend.dto.PasskeyOptionsDto;
import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.MfaFactorRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.WebAuthnCeremonyRepository;
import com.roadwise.backend.repository.WebAuthnUserHandleRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.PasskeyService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import com.roadwise.backend.service.TurnstileService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login-ceremony integration for the passkey branch of MFA.
 *
 * <p>The contract under test is the one that matters for account safety: the normal
 * JWT/refresh-token response is issued only after the assertion succeeds and the
 * challenge is consumed. A failed assertion gets a refusal and nothing else -- no
 * tokens, no refresh row, and one attempt closer to the challenge's budget.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthControllerWebAuthnTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;
    @Autowired
    private MfaFactorRepository mfaFactorRepository;
    @Autowired
    private WebAuthnUserHandleRepository userHandleRepository;
    @Autowired
    private WebAuthnCeremonyRepository ceremonyRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private PasskeyService passkeyService;
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

    /**
     * The login step is the entry to the ceremony under test, and its Turnstile check is
     * stubbed: the verdict is not what this class is about, and reaching
     * challenges.cloudflare.com from a unit test would make it depend on the network.
     */
    @MockitoBean
    private TurnstileService turnstileService;

    private User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        when(turnstileService.verify(any(), any()))
                .thenReturn(new TurnstileService.Result(true, null));

        loginRateLimitRepository.deleteAll();
        ceremonyRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        mfaFactorRepository.deleteAll();
        userHandleRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("passkeylogin");
        testUser.setPassword(passwordEncoder.encode("Password123!"));
        testUser.setEmail("passkeylogin@example.com");
        testUser.setRole("BARANGAY");
        testUser.setFirstName("Pass");
        testUser.setLastName("Key");
        testUser.setStatus("Active");
        testUser = userRepository.save(testUser);

        MfaFactor passkey = new MfaFactor(
                testUser, MfaFactor.TYPE_WEBAUTHN, "My passkey", MfaFactor.STATUS_ACTIVE);
        passkey.setCredentialId(new byte[]{1, 2, 3, 4});
        passkey.setPublicKey(new byte[]{(byte) 0xA5, 1, 2, 3});
        mfaFactorRepository.save(passkey);

        when(passkeyService.isConfigured()).thenReturn(true);
    }

    @Test
    void selectOffersTheCeremonyKeyBoundToTheLoginChallenge() throws Exception {
        when(passkeyService.beginAuthentication(any(), any()))
                .thenReturn(new PasskeyOptionsDto<>("ceremony-key-1", null));

        String mfaToken = loginAndGetMfaToken();

        mockMvc.perform(post("/api/auth/mfa/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"factorType\":\"WEBAUTHN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passkey.ceremonyKey").value("ceremony-key-1"))
                .andExpect(jsonPath("$.factorType").value("WEBAUTHN"));
    }

    @Test
    void selectAnswersServiceUnavailableWhenPasskeysAreUnconfigured() throws Exception {
        when(passkeyService.isConfigured()).thenReturn(false);
        String mfaToken = loginAndGetMfaToken();

        mockMvc.perform(post("/api/auth/mfa/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"factorType\":\"WEBAUTHN\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void failedAssertionIssuesNoTokensAndBurnsAnAttempt() throws Exception {
        when(passkeyService.verifyAssertion(any(), any(), eq("ceremony-key-1"), any()))
                .thenReturn(new PasskeyService.AssertionResult(false, "VERIFICATION_FAILED"));
        String mfaToken = loginAndGetMfaToken();
        selectPasskeyFactor(mfaToken);

        long refreshRowsBefore = refreshTokenRepository.count();

        mockMvc.perform(post("/api/auth/verify-mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assertionBody(mfaToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        assertEquals(refreshRowsBefore, refreshTokenRepository.count(),
                "a failed assertion must not mint a refresh-token session");
        MfaChallenge challenge = mfaChallengeRepository
                .findByChallengeTokenHash(com.roadwise.backend.util.TokenHashes.sha256Hex(mfaToken))
                .orElseThrow();
        assertEquals(2, challenge.getAttempts(),
                "select charges one attempt and the failed proof one more");
        assertEquals(false, challenge.isUsed());
    }

    @Test
    void successfulAssertionIssuesTheNormalSessionExactlyOnce() throws Exception {
        when(passkeyService.verifyAssertion(any(), any(), eq("ceremony-key-1"), any()))
                .thenReturn(new PasskeyService.AssertionResult(true, null));
        String mfaToken = loginAndGetMfaToken();
        selectPasskeyFactor(mfaToken);

        mockMvc.perform(post("/api/auth/verify-mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assertionBody(mfaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertEquals(1, refreshTokenRepository.count());
        MfaChallenge challenge = mfaChallengeRepository
                .findByChallengeTokenHash(com.roadwise.backend.util.TokenHashes.sha256Hex(mfaToken))
                .orElseThrow();
        assertEquals(true, challenge.isUsed());

        // The challenge is single-use: the same body cannot buy a second session.
        mockMvc.perform(post("/api/auth/verify-mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assertionBody(mfaToken)))
                .andExpect(status().isUnauthorized());
        assertEquals(1, refreshTokenRepository.count());
    }

    // ---------------------------------------------------------------

    private String loginAndGetMfaToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"passkeylogin\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.mfaToken");
    }

    private String assertionBody(String mfaToken) {
        return "{"
                + "\"mfaToken\":\"" + mfaToken + "\","
                + "\"ceremonyKey\":\"ceremony-key-1\","
                + "\"assertion\":{"
                + "  \"id\":\"AQID\","
                + "  \"rawId\":\"AQID\","
                + "  \"type\":\"public-key\","
                + "  \"response\":{"
                + "    \"clientDataJSON\":\"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0\","
                + "    \"authenticatorData\":\"AQIDBA\","
                + "    \"signature\":\"BwgJ\""
                + "  }"
                + "}"
                + "}";
    }
    /** Selects the passkey factor the way the real frontend does, before verifying. */
    private void selectPasskeyFactor(String mfaToken) throws Exception {
        when(passkeyService.beginAuthentication(any(), any()))
                .thenReturn(new PasskeyOptionsDto<>("ceremony-key-1", null));
        mockMvc.perform(post("/api/auth/mfa/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"factorType\":\"WEBAUTHN\"}"))
                .andExpect(status().isOk());
    }
}
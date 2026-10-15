package com.roadwise.backend.service;

import com.roadwise.backend.dto.PasskeyOptionsDto;
import com.roadwise.backend.dto.WebAuthnCredentialDto;
import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.model.WebAuthnCeremony;
import com.roadwise.backend.model.WebAuthnUserHandle;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.MfaFactorRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.WebAuthnCeremonyRepository;
import com.roadwise.backend.repository.WebAuthnUserHandleRepository;
import com.roadwise.backend.util.TokenHashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The service-level binding and consumption matrix, against a configured relying party
 * (localhost, loopback http) so the real bean graph is exercised.
 *
 * <p>Cryptographic verification itself is webauthn4j's contract, tested upstream; what
 * is tested here is everything this application adds around it: ceremony ownership,
 * challenge binding, what happens on each failure class, and -- the load-bearing rule --
 * that a failed verification consumes the ceremony rather than re-opening it.
 */
@SpringBootTest(properties = {
        "webauthn.rp-id=localhost",
        "webauthn.allowed-origins=http://localhost:63342"
})
@ActiveProfiles("test")
class PasskeyServiceTest {

    @Autowired
    private PasskeyService passkeyService;
    @Autowired
    private WebAuthnCeremonyService ceremonies;
    @Autowired
    private WebAuthnCeremonyRepository ceremonyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MfaChallengeRepository mfaChallengeRepository;
    @Autowired
    private MfaFactorRepository mfaFactorRepository;
    @Autowired
    private WebAuthnUserHandleRepository userHandleRepository;
    @Autowired
    private WebAuthnCredentialRepository credentialRepository;

    private User user;
    private byte[] userHandle;
    private MfaChallenge challenge;

    private static final byte[] CREDENTIAL_ID = {1, 2, 3, 4};

    @BeforeEach
    void setUp() {
        ceremonyRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        mfaFactorRepository.deleteAll();
        userHandleRepository.deleteAll();
        userRepository.deleteAll();

        user = newUser("passkey-user", "passkey@example.com");
        userHandle = new byte[]{5, 6, 7, 8, 9, 10, 11, 12};
        userHandleRepository.save(new WebAuthnUserHandle(user.getId(), userHandle, "Passkey User"));
        challenge = newChallenge(user, "challenge-a");
    }

    @Test
    void beginRegistrationIssuesACeremonyBoundToTheAccount() {
        PasskeyOptionsDto<?> dto = passkeyService.beginRegistration(user, authenticationFor(user));

        assertNotNull(dto.ceremonyKey());
        WebAuthnCeremony row = ceremonyRepository
                .findById(TokenHashes.sha256Hex(dto.ceremonyKey())).orElseThrow();
        assertEquals(WebAuthnCeremony.TYPE_REGISTRATION, row.getCeremonyType());
        assertEquals(user.getId(), row.getUserId());
        assertArrayEquals(userHandle, row.getUserHandle());
        assertEquals(WebAuthnCeremony.STATUS_ISSUED, row.getStatus());
    }

    @Test
    void beginRegistrationRefusesAForeignPrincipal() {
        User other = newUser("someone-else", "someone@example.com");

        assertThrows(IllegalStateException.class,
                () -> passkeyService.beginRegistration(user, authenticationFor(other)));
    }

    @Test
    void beginAuthenticationListsActiveCredentialsAndBindsTheChallenge() {
        seedActivePasskey(user, CREDENTIAL_ID);

        PasskeyOptionsDto<PublicKeyCredentialRequestOptions> dto =
                passkeyService.beginAuthentication(user, challenge.getId());

        assertEquals(1, dto.options().getAllowCredentials().size());
        assertArrayEquals(CREDENTIAL_ID,
                dto.options().getAllowCredentials().get(0).getId().getBytes());
        WebAuthnCeremony row = ceremonyRepository
                .findById(TokenHashes.sha256Hex(dto.ceremonyKey())).orElseThrow();
        assertEquals(challenge.getId(), row.getMfaChallengeId());
    }

    @Test
    void beginAuthenticationWithoutAPasskeyRefuses() {
        assertThrows(IllegalStateException.class,
                () -> passkeyService.beginAuthentication(user, challenge.getId()));
    }

    @Test
    void assertionAgainstAnotherChallengesCeremonyIsRefusedAndConsumed() {
        seedActivePasskey(user, CREDENTIAL_ID);
        MfaChallenge other = newChallenge(user, "challenge-b");
        PasskeyOptionsDto<PublicKeyCredentialRequestOptions> issued =
                passkeyService.beginAuthentication(user, other.getId());

        PasskeyService.AssertionResult result = passkeyService.verifyAssertion(
                user, challenge, issued.ceremonyKey(), garbageAssertion());

        assertFalse(result.success());
        assertEquals("WRONG_CHALLENGE", result.code());
        // Consumed, not re-opened: the binding failure is a recorded verification failure.
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, ceremonyRepository
                .findById(TokenHashes.sha256Hex(issued.ceremonyKey())).orElseThrow().getStatus());
        assertEquals("FAILED_WRONG_BINDING", ceremonyRepository
                .findById(TokenHashes.sha256Hex(issued.ceremonyKey())).orElseThrow().getOutcome());
    }

    @Test
    void malformedCredentialIsRefusedBeforeTheCeremonyIsTouched() {
        seedActivePasskey(user, CREDENTIAL_ID);
        PasskeyOptionsDto<PublicKeyCredentialRequestOptions> issued =
                passkeyService.beginAuthentication(user, challenge.getId());
        WebAuthnCredentialDto malformed = new WebAuthnCredentialDto();
        malformed.setId("AQID");
        // response missing entirely: cannot be parsed into a typed credential.

        PasskeyService.AssertionResult result = passkeyService.verifyAssertion(
                user, challenge, issued.ceremonyKey(), malformed);

        assertEquals("MALFORMED_CREDENTIAL", result.code());
        // Parsing is not a verification attempt: the ceremony stays claimable.
        assertInstanceOf(WebAuthnCeremonyService.ClaimResult.Claimed.class,
                ceremonies.claim(issued.ceremonyKey(), WebAuthnCeremony.TYPE_AUTHENTICATION));
    }

    @Test
    void unverifiableAssertionConsumesTheCeremonyWithACommittedFailure() {
        seedActivePasskey(user, CREDENTIAL_ID);
        PasskeyOptionsDto<PublicKeyCredentialRequestOptions> issued =
                passkeyService.beginAuthentication(user, challenge.getId());

        // Parses as a typed credential but cannot pass cryptographic verification:
        // the exactly-a-failure case that must consume state instead of throwing.
        PasskeyService.AssertionResult result = passkeyService.verifyAssertion(
                user, challenge, issued.ceremonyKey(), garbageAssertion());

        assertFalse(result.success());
        assertEquals("VERIFICATION_FAILED", result.code());
        WebAuthnCeremony row = ceremonyRepository
                .findById(TokenHashes.sha256Hex(issued.ceremonyKey())).orElseThrow();
        assertEquals(WebAuthnCeremony.STATUS_CONSUMED, row.getStatus());
        assertEquals("FAILED_VERIFICATION", row.getOutcome());
        // And the consumed state refuses a replay.
        assertEquals(WebAuthnCeremonyService.RejectReason.ALREADY_CONSUMED,
                ((WebAuthnCeremonyService.ClaimResult.Rejected)
                        ceremonies.claim(issued.ceremonyKey(),
                                WebAuthnCeremony.TYPE_AUTHENTICATION)).reason());
    }

    @Test
    void missingAssertionInputFailsWithoutAKeyToConsume() {
        PasskeyService.AssertionResult result =
                passkeyService.verifyAssertion(user, challenge, null, null);
        assertEquals("MISSING_INPUT", result.code());
    }

    @Test
    void completingARegistrationForAnotherAccountIsRefusedAndConsumed() {
        PasskeyOptionsDto<?> issued = passkeyService.beginRegistration(user, authenticationFor(user));
        User intruder = newUser("intruder", "intruder@example.com");

        PasskeyService.RegistrationResult result = passkeyService.completeRegistration(
                intruder, issued.ceremonyKey(), garbageAttestation(), "Nope");

        assertFalse(result.success());
        assertEquals("WRONG_USER", result.code());
        assertEquals("FAILED_WRONG_USER", ceremonyRepository
                .findById(TokenHashes.sha256Hex(issued.ceremonyKey())).orElseThrow().getOutcome());
    }

    @Test
    void unverifiableAttestationConsumesTheRegistrationCeremony() {
        PasskeyOptionsDto<?> issued = passkeyService.beginRegistration(user, authenticationFor(user));

        PasskeyService.RegistrationResult result = passkeyService.completeRegistration(
                user, issued.ceremonyKey(), garbageAttestation(), "Test key");

        assertFalse(result.success());
        assertEquals("VERIFICATION_FAILED", result.code());
        WebAuthnCeremony row = ceremonyRepository
                .findById(TokenHashes.sha256Hex(issued.ceremonyKey())).orElseThrow();
        assertEquals("FAILED_VERIFICATION", row.getOutcome());
    }

    @Test
    void disabledPasskeysDisappearFromAssertionLookup() {
        seedActivePasskey(user, CREDENTIAL_ID);
        assertNotNull(credentialRepository.findByCredentialId(new Bytes(CREDENTIAL_ID)));

        // The management flow's disable path (also what the credential adapter's own
        // delete uses): status DISABLED plus the shared key-material wipe.
        MfaFactor factor = mfaFactorRepository.findByCredentialId(CREDENTIAL_ID).orElseThrow();
        factor.setStatus(MfaFactor.STATUS_DISABLED);
        factor.clearKeyMaterial();
        mfaFactorRepository.save(factor);

        assertNull(credentialRepository.findByCredentialId(new Bytes(CREDENTIAL_ID)));
        // An assertion naming this credential can no longer even find a record to verify.
    }

    // ---------------------------------------------------------------

    private User newUser(String username, String email) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setEmail(email);
        u.setRole("BARANGAY");
        u.setStatus("Active");
        return userRepository.save(u);
    }

    private MfaChallenge newChallenge(User owner, String tokenHash) {
        MfaChallenge c = MfaChallenge.forToken(
                owner, tokenHash, LocalDateTime.now().plusMinutes(10));
        c.setFactorType(MfaFactor.TYPE_WEBAUTHN);
        return mfaChallengeRepository.save(c);
    }

    private void seedActivePasskey(User owner, byte[] credentialId) {
        MfaFactor factor = new MfaFactor(
                owner, MfaFactor.TYPE_WEBAUTHN, "Test key", MfaFactor.STATUS_ACTIVE);
        factor.setCredentialId(credentialId);
        factor.setPublicKey(new byte[]{(byte) 0xA5, 1, 2, 3});
        mfaFactorRepository.save(factor);
    }

    private Authentication authenticationFor(User owner) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                owner.getUsername(), "n/a", java.util.List.of());
        token.setAuthenticated(true);
        return token;
    }

    private WebAuthnCredentialDto garbageAssertion() {
        WebAuthnCredentialDto dto = new WebAuthnCredentialDto();
        dto.setId("AQID");
        dto.setRawId("AQID");
        dto.setType("public-key");
        WebAuthnCredentialDto.Response response = new WebAuthnCredentialDto.Response();
        response.setClientDataJSON("eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0");
        response.setAuthenticatorData("AQIDBA");
        response.setSignature("BwgJ");
        dto.setResponse(response);
        return dto;
    }

    private WebAuthnCredentialDto garbageAttestation() {
        WebAuthnCredentialDto dto = new WebAuthnCredentialDto();
        dto.setId("BAUG");
        dto.setRawId("BAUG");
        dto.setType("public-key");
        WebAuthnCredentialDto.Response response = new WebAuthnCredentialDto.Response();
        response.setClientDataJSON("eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIn0");
        response.setAttestationObject("o21mb3JnZWRLaWRdBQE");
        dto.setResponse(response);
        return dto;
    }
}
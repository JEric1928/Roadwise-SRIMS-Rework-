package com.roadwise.backend.service;

import com.roadwise.backend.config.WebAuthnProperties;
import com.roadwise.backend.dto.PasskeyOptionsDto;
import com.roadwise.backend.dto.WebAuthnCredentialDto;
import com.roadwise.backend.model.MfaChallenge;
import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.model.WebAuthnCeremony;
import com.roadwise.backend.repository.MfaFactorRepository;
import com.roadwise.backend.repository.WebAuthnUserHandleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The four WebAuthn protocol operations, run over {@link WebAuthnCeremonyService}'s
 * explicit-key store instead of Spring's session-bound options repositories.
 *
 * <p>Ownership binding is explicit in both directions. Registration claims a ceremony
 * issued for {@code user.getId()}; assertion claims a ceremony issued for the exact
 * pre-authentication {@link MfaChallenge} being verified. After the relying-party
 * operations verify the cryptography, the returned owner handle is mapped back to an
 * account id and re-checked against the caller -- so even a valid assertion for some
 * other account's passkey cannot complete this login.
 *
 * <p>Transaction contract, inherited from the ceremony service and the reason these
 * methods return results instead of throwing on verification failure: claim, verify and
 * outcome must commit together. A runtime exception escaping here would roll the whole
 * thing back, un-consuming the ceremony and re-opening it for replay. Expected failures
 * (bad origin, bad signature, wrong owner) are therefore converted into a committed
 * {@code FAILED_*} outcome plus a failure result; only unexpected infrastructure errors
 * propagate.
 */
@Service
public class PasskeyService {

    /** How long an issued ceremony stays claimable. The browser prompt is immediate. */
    static final Duration CEREMONY_TTL = Duration.ofMinutes(5);

    private final WebAuthnProperties properties;
    private final ObjectProvider<WebAuthnRelyingPartyOperations> relyingParty;
    private final WebAuthnCeremonyService ceremonies;
    private final WebAuthnUserHandleRepository userHandleRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final MfaFactorRepository mfaFactorRepository;
    private final JsonMapper jsonMapper;

    public PasskeyService(WebAuthnProperties properties,
                          ObjectProvider<WebAuthnRelyingPartyOperations> relyingParty,
                          WebAuthnCeremonyService ceremonies,
                          WebAuthnUserHandleRepository userHandleRepository,
                          WebAuthnCredentialRepository credentialRepository,
                          MfaFactorRepository mfaFactorRepository,
                          JsonMapper jsonMapper) {
        this.properties = properties;
        this.relyingParty = relyingParty;
        this.ceremonies = ceremonies;
        this.userHandleRepository = userHandleRepository;
        this.credentialRepository = credentialRepository;
        this.mfaFactorRepository = mfaFactorRepository;
        this.jsonMapper = jsonMapper;
    }

    /** @return true when the relying party is configured; false makes every operation inert. */
    public boolean isConfigured() {
        return properties.isConfigured() && relyingParty.getIfAvailable() != null;
    }

    /** Result of a registration ceremony. */
    public record RegistrationResult(boolean success, String code, MfaFactor factor) {
        static RegistrationResult ok(MfaFactor factor) { return new RegistrationResult(true, null, factor); }
        static RegistrationResult fail(String code) { return new RegistrationResult(false, code, null); }
    }

    /** Result of an assertion ceremony. */
    public record AssertionResult(boolean success, String code) {
        static AssertionResult ok() { return new AssertionResult(true, null); }
        static AssertionResult fail(String code) { return new AssertionResult(false, code); }
    }

    // ---------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------

    /**
     * Opens a registration ceremony for the authenticated user.
     *
     * <p>The relying-party operations resolve the user handle (minting it on first use)
     * and populate {@code excludeCredentials} with the account's existing passkeys, so a
     * second attempt on an already-registered authenticator is refused by the browser
     * rather than silently enrolling a duplicate.
     */
    @Transactional
    public PasskeyOptionsDto<PublicKeyCredentialCreationOptions> beginRegistration(
            User user, Authentication authentication) {
        requireRelyingParty();
        if (authentication == null || authentication.getName() == null
                || !authentication.getName().equals(user.getUsername())) {
            throw new IllegalStateException("Passkey enrolment requires the authenticated account's own session.");
        }
        PublicKeyCredentialCreationOptions options = requireRelyingParty()
                .createPublicKeyCredentialCreationOptions(
                        new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
        byte[] userHandle = userHandleRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No user handle exists for this account."))
                .getUserHandle();
        String ceremonyKey = ceremonies.beginRegistration(
                user.getId(), userHandle, jsonMapper.writeValueAsString(options), CEREMONY_TTL);
        return new PasskeyOptionsDto<>(ceremonyKey, options);
    }

    /**
     * Verifies an attestation and enrols the credential as a WEBAUTHN factor.
     *
     * <p>Ownership is checked twice: the ceremony must have been issued for this account,
     * and the credential's user handle (embedded in the attested data) must resolve back
     * to this account. A failure at either point commits a {@code FAILED_*} outcome and
     * returns rather than throwing -- see the class contract.
     */
    @Transactional
    public RegistrationResult completeRegistration(User user, String ceremonyKey,
                                                   WebAuthnCredentialDto credentialDto, String label) {
        requireRelyingParty();
        if (ceremonyKey == null || ceremonyKey.isBlank() || credentialDto == null) {
            return RegistrationResult.fail("MISSING_INPUT");
        }
        // Parse before claiming: malformed input is not a verification attempt, and the
        // ceremony stays live for the legitimate client to retry.
        PublicKeyCredential<AuthenticatorAttestationResponse> credential;
        try {
            credential = readTyped(credentialDto, new TypeReference<>() {
            });
        } catch (RuntimeException malformed) {
            return RegistrationResult.fail("MALFORMED_CREDENTIAL");
        }

        WebAuthnCeremonyService.ClaimResult claim =
                ceremonies.claim(ceremonyKey, WebAuthnCeremony.TYPE_REGISTRATION);
        if (claim instanceof WebAuthnCeremonyService.ClaimResult.Rejected rejected) {
            return RegistrationResult.fail("CEREMONY_" + rejected.reason());
        }
        WebAuthnCeremonyService.ClaimedCeremony ceremony =
                ((WebAuthnCeremonyService.ClaimResult.Claimed) claim).ceremony();
        if (!user.getId().equals(ceremony.userId())) {
            ceremonies.complete(ceremonyKey, "FAILED_WRONG_USER");
            return RegistrationResult.fail("WRONG_USER");
        }

        try {
            PublicKeyCredentialCreationOptions options =
                    registrationOptionsFrom(ceremony.optionsJson());
            CredentialRecord record = requireRelyingParty().registerCredential(
                    new ImmutableRelyingPartyRegistrationRequest(options,
                            new RelyingPartyPublicKey(credential,
                                    label == null || label.isBlank() ? "Passkey" : label.trim())));
            // The relying-party operations persist through WebAuthnCredentialRepository,
            // which upserts the WEBAUTHN factor row; re-read it for the API response.
            ceremonies.complete(ceremonyKey, "COMPLETED");
            MfaFactor factor = mfaFactorRepository
                    .findByCredentialId(record.getCredentialId().getBytes())
                    .orElse(null);
            return RegistrationResult.ok(factor);
        } catch (RuntimeException e) {
            // Commit the refusal; the ceremony stays consumed and the failure recorded.
            ceremonies.complete(ceremonyKey, "FAILED_VERIFICATION");
            return RegistrationResult.fail("VERIFICATION_FAILED");
        }
    }

    // ---------------------------------------------------------------
    // Authentication (login assertion)
    // ---------------------------------------------------------------

    /**
     * Opens an assertion ceremony bound to the login's MFA challenge.
     *
     * <p>{@code allowCredentials} lists the account's active passkeys, which is what lets
     * a platform authenticator pick the right one without the user typing anything.
     */
    @Transactional
    public PasskeyOptionsDto<PublicKeyCredentialRequestOptions> beginAuthentication(User user, Long mfaChallengeId) {
        requireRelyingParty();
        List<PublicKeyCredentialDescriptor> allowCredentials = userHandleRepository.findByUserId(user.getId())
                .map(handle -> credentialRepository.findByUserId(new Bytes(handle.getUserHandle())).stream()
                        .map(record -> PublicKeyCredentialDescriptor.builder()
                                .type(PublicKeyCredentialType.PUBLIC_KEY)
                                .id(record.getCredentialId())
                                .build())
                        .toList())
                .orElseGet(List::of);
        if (allowCredentials.isEmpty()) {
            throw new IllegalStateException("No passkey is enrolled on this account.");
        }
        PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .rpId(properties.getRpId())
                .allowCredentials(allowCredentials)
                .userVerification(UserVerificationRequirement.PREFERRED)
                .timeout(CEREMONY_TTL)
                .build();
        String ceremonyKey = ceremonies.beginAuthentication(
                mfaChallengeId, jsonMapper.writeValueAsString(options), CEREMONY_TTL);
        return new PasskeyOptionsDto<>(ceremonyKey, options);
    }

    /**
     * Verifies an assertion against the login challenge's ceremony.
     *
     * <p>The credential is parsed before the ceremony is claimed, so a malformed payload
     * costs nothing: parsing is not a verification attempt and the ceremony stays live
     * for the legitimate client. Once claimed, the owner returned by the cryptographic
     * verification is mapped to an account and compared with the challenge's account --
     * a valid passkey belonging to someone else fails here, with the outcome committed.
     */
    @Transactional
    public AssertionResult verifyAssertion(User user, MfaChallenge challenge, String ceremonyKey,
                                           WebAuthnCredentialDto credentialDto) {
        requireRelyingParty();
        if (ceremonyKey == null || ceremonyKey.isBlank() || credentialDto == null) {
            return AssertionResult.fail("MISSING_INPUT");
        }
        PublicKeyCredential<AuthenticatorAssertionResponse> credential;
        try {
            credential = readTyped(credentialDto, new TypeReference<>() {
            });
        } catch (RuntimeException malformed) {
            return AssertionResult.fail("MALFORMED_CREDENTIAL");
        }

        WebAuthnCeremonyService.ClaimResult claim =
                ceremonies.claim(ceremonyKey, WebAuthnCeremony.TYPE_AUTHENTICATION);
        if (claim instanceof WebAuthnCeremonyService.ClaimResult.Rejected rejected) {
            return AssertionResult.fail("CEREMONY_" + rejected.reason());
        }
        WebAuthnCeremonyService.ClaimedCeremony ceremony =
                ((WebAuthnCeremonyService.ClaimResult.Claimed) claim).ceremony();
        if (!challenge.getId().equals(ceremony.mfaChallengeId())) {
            ceremonies.complete(ceremonyKey, "FAILED_WRONG_BINDING");
            return AssertionResult.fail("WRONG_CHALLENGE");
        }
        if (!user.getId().equals(challenge.getUser().getId())) {
            ceremonies.complete(ceremonyKey, "FAILED_WRONG_USER");
            return AssertionResult.fail("WRONG_USER");
        }

        try {
            PublicKeyCredentialRequestOptions options =
                    assertionOptionsFrom(ceremony.optionsJson());
            PublicKeyCredentialUserEntity owner = requireRelyingParty()
                    .authenticate(new RelyingPartyAuthenticationRequest(options, credential));
            Long ownerId = userHandleRepository.findByUserHandle(owner.getId().getBytes())
                    .map(handle -> handle.getUserId())
                    .orElse(null);
            if (!user.getId().equals(ownerId)) {
                ceremonies.complete(ceremonyKey, "FAILED_WRONG_OWNER");
                return AssertionResult.fail("WRONG_OWNER");
            }
            ceremonies.complete(ceremonyKey, "COMPLETED");
            return AssertionResult.ok();
        } catch (RuntimeException e) {
            ceremonies.complete(ceremonyKey, "FAILED_VERIFICATION");
            return AssertionResult.fail("VERIFICATION_FAILED");
        }
    }

    // ---------------------------------------------------------------

    /**
     * @throws IllegalStateException when passkeys are not configured on this server —
     *         the inert direction, so an unwired factor can never be exercised by
     *         arriving at this service another way.
     */
    private WebAuthnRelyingPartyOperations requireRelyingParty() {
        WebAuthnRelyingPartyOperations rp = relyingParty.getIfAvailable();
        if (rp == null) {
            throw new IllegalStateException(
                    "Passkeys are not available on this server: webauthn.rp-id and webauthn.allowed-origins are not configured.");
        }
        return rp;
    }

    /**
     * Rebuilds the verification-relevant view of the stored creation options.
     *
     * <p>The module's mixins serialise the option types but deliberately do not
     * deserialise them -- Spring's own repositories keep options as live objects in the
     * session and never round-trip them through JSON. The ceremony store is JSON, so
     * verification re-derives exactly the fields the relying-party operations read (rp
     * id, challenge, key parameters, user verification) from the very document that was
     * issued, instead of a parallel copy that could drift from it.
     */
    private PublicKeyCredentialCreationOptions registrationOptionsFrom(String optionsJson) {
        JsonNode json = readTree(optionsJson);
        JsonNode rp = json.path("rp");
        List<PublicKeyCredentialParameters> params = new ArrayList<>();
        for (JsonNode param : json.path("pubKeyCredParams")) {
            params.add(coseParameters(param.path("alg").asLong()));
        }
        if (params.isEmpty()) {
            params.add(PublicKeyCredentialParameters.ES256);
        }
        return PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder()
                        .name(rp.path("name").asText("RoadWise"))
                        .id(rp.path("id").asText(properties.getRpId()))
                        .build())
                .challenge(Bytes.fromBase64(json.path("challenge").asText()))
                .pubKeyCredParams(params)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build())
                .build();
    }

    /** The assertion-side twin of {@link #registrationOptionsFrom}. */
    private PublicKeyCredentialRequestOptions assertionOptionsFrom(String optionsJson) {
        JsonNode json = readTree(optionsJson);
        List<PublicKeyCredentialDescriptor> allowCredentials = new ArrayList<>();
        for (JsonNode descriptor : json.path("allowCredentials")) {
            allowCredentials.add(PublicKeyCredentialDescriptor.builder()
                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                    .id(Bytes.fromBase64(descriptor.path("id").asText()))
                    .build());
        }
        return PublicKeyCredentialRequestOptions.builder()
                .rpId(json.path("rpId").asText(properties.getRpId()))
                .challenge(Bytes.fromBase64(json.path("challenge").asText()))
                .allowCredentials(allowCredentials)
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build();
    }

    /** Maps a stored COSE algorithm id back to the module's constant; unknown fails closed. */
    private static PublicKeyCredentialParameters coseParameters(long alg) {
        return switch ((int) alg) {
            case -7 -> PublicKeyCredentialParameters.ES256;
            case -8 -> PublicKeyCredentialParameters.EdDSA;
            case -257 -> PublicKeyCredentialParameters.RS256;
            default -> throw new IllegalArgumentException(
                    "Unsupported COSE algorithm in stored options: " + alg);
        };
    }

    private JsonNode readTree(String json) {
        return jsonMapper.readTree(json);
    }

    /**
     * Re-parses the browser DTO through the WebAuthn-configured mapper into a typed
     * credential, so the browser wire shape (base64url strings) becomes the typed
     * {@code Bytes} fields verification uses -- one mapper, one interpretation.
     */
    private <R extends AuthenticatorResponse> PublicKeyCredential<R> readTyped(
            WebAuthnCredentialDto dto, TypeReference<PublicKeyCredential<R>> type) {
        return jsonMapper.readValue(writeJson(dto), type);
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        return jsonMapper.readValue(json, type);
    }

    private String writeJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }
}
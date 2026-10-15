package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import java.util.List;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the WebAuthn Jackson module is registered on the auto-configured
 * {@link JsonMapper} -- the same instance Spring MVC serialises every response with,
 * and the same one the service layer parses credentials through.
 *
 * <p>Both directions matter: options we hand to the browser must come out in the spec's
 * base64url wire shape, and the browser's credential object must come back in as the
 * module's typed values. If either direction silently fell back to default Jackson
 * behaviour, {@code Bytes} would serialise as a POJO and every real ceremony would fail
 * against a live authenticator while unit tests stayed green.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebAuthnJacksonModuleTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void requestOptionsSerialiseInTheBrowserWireShape() {
        byte[] challenge = {1, 2, 3, 4, 5, 6, 7, 8};
        PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                .challenge(new Bytes(challenge))
                .rpId("localhost")
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build();

        String json = jsonMapper.writeValueAsString(options);

        assertTrue(json.contains("\"challenge\":\"" + new Bytes(challenge).toBase64UrlString() + "\""),
                "challenge must be base64url, got: " + json);
        assertTrue(json.contains("\"rpId\":\"localhost\""));
        assertTrue(json.contains("\"userVerification\":\"preferred\""),
                "enum must serialise as its spec value, got: " + json);
    }

    @Test
    void creationOptionsSerialiseInTheBrowserWireShape() {
        byte[] challenge = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder().name("RoadWise").id("localhost").build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .name("testuser")
                        .id(new Bytes(new byte[16]))
                        .displayName("Test User")
                        .build())
                .challenge(new Bytes(challenge))
                .pubKeyCredParams(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build())
                .build();

        String json = jsonMapper.writeValueAsString(options);

        assertTrue(json.contains("\"challenge\":\"" + new Bytes(challenge).toBase64UrlString() + "\""),
                "challenge must be base64url, got: " + json);
        assertTrue(json.contains("\"id\":\"localhost\""), "rp id must be present, got: " + json);
        assertTrue(json.contains("\"alg\":-7"), "ES256 must serialise as its COSE id, got: " + json);
        // Note: the option types are serialise-only by design (Spring keeps them as live
        String userHandle = new Bytes(new byte[16]).toBase64UrlString();
        assertTrue(json.contains("\"id\":\"" + userHandle + "\""),
                "user.id must serialise as base64url so the browser can decode it, got: " + json);
        // objects in the session); verification rebuilds them from this document instead.
    }

    @Test
    void requestOptionsSerialiseAllowCredentialIdsAsBase64Url() {
        // Distinct byte runs, so the assertions cannot pass by matching the challenge.
        byte[] challenge = {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        byte[] credentialId = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                .rpId("localhost")
                .challenge(new Bytes(challenge))
                .allowCredentials(List.of(PublicKeyCredentialDescriptor.builder()
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .id(new Bytes(credentialId))
                        .build()))
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build();

        String json = jsonMapper.writeValueAsString(options);

        assertTrue(json.contains("\"rpId\":\"localhost\""), "rpId must be present, got: " + json);
        assertTrue(json.contains("\"challenge\":\"" + new Bytes(challenge).toBase64UrlString() + "\""),
                "challenge must be base64url, got: " + json);
        // The browser-decoded field: js/core/webauthn.js turns this back into an ArrayBuffer.
        assertTrue(json.contains("\"id\":\"" + new Bytes(credentialId).toBase64UrlString() + "\""),
                "allowCredentials[].id must serialise as base64url, got: " + json);
    }

    @Test
    void assertionCredentialDeserialisesIntoTypedBytes() {
        String json = """
                {
                  "id": "AQID",
                  "rawId": "AQID",
                  "type": "public-key",
                  "response": {
                    "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0",
                    "authenticatorData": "AQIDBA",
                    "signature": "BwgJ",
                    "userHandle": "AQIDBAUGBwgJCgsMDQ4PEA"
                  },
                  "clientExtensionResults": {}
                }
                """;

        PublicKeyCredential<AuthenticatorAssertionResponse> credential = jsonMapper.readValue(
                json, new TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>() {
                });

        assertEquals("AQID", credential.getId());
        assertArrayEquals(new byte[]{1, 2, 3}, credential.getRawId().getBytes());
        AuthenticatorAssertionResponse response = credential.getResponse();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, response.getAuthenticatorData().getBytes());
        assertArrayEquals(new byte[]{7, 8, 9}, response.getSignature().getBytes());
        assertEquals(16, response.getUserHandle().getBytes().length);
    }

    @Test
    void attestationCredentialDeserialisesWithTransports() {
        String json = """
                {
                  "id": "BAUG",
                  "rawId": "BAUG",
                  "type": "public-key",
                  "response": {
                    "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIn0",
                    "attestationObject": "o21mb3JnZWRLaWRdBQE",
                    "transports": ["internal", "hybrid"]
                  }
                }
                """;

        PublicKeyCredential<AuthenticatorAttestationResponse> credential = jsonMapper.readValue(
                json, new TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>>() {
                });

        assertArrayEquals(new byte[]{4, 5, 6}, credential.getRawId().getBytes());
        assertNotNull(credential.getResponse().getAttestationObject());
        assertEquals(2, credential.getResponse().getTransports().size());
    }
}
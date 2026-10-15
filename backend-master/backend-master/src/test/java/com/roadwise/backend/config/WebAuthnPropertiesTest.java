package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fail-closed contract for passkey configuration, mirroring {@code mfa.encryption-key}:
 * unset is inert, malformed is fatal at startup, never discovered at the first ceremony.
 */
class WebAuthnPropertiesTest {

    @Test
    void unsetIsInertRatherThanBroken() {
        WebAuthnProperties properties = new WebAuthnProperties("", "");
        assertFalse(properties.isConfigured());
        assertTrue(properties.getAllowedOrigins().isEmpty());
    }

    @Test
    void settingOnlyOneHalfIsFatal() {
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph", ""));
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("", "https://roadwise.example.gov.ph"));
    }

    @Test
    void copyPastedUrlAsRpIdIsFatal() {
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("https://roadwise.example.gov.ph",
                        "https://roadwise.example.gov.ph"));
    }

    @Test
    void rpIdWithPortOrTrailingDotIsFatal() {
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph:8443",
                        "https://roadwise.example.gov.ph:8443"));
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph.",
                        "https://roadwise.example.gov.ph"));
    }

    @Test
    void uppercaseRpIdIsNormalisedNotRejected() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "Roadwise.Example.Gov.Ph", "https://Roadwise.Example.Gov.Ph");
        assertTrue(properties.isConfigured());
        assertEquals("roadwise.example.gov.ph", properties.getRpId());
    }

    @Test
    void httpOnPublicHostIsFatalButLoopbackIsAllowed() {
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph",
                        "http://roadwise.example.gov.ph"));
        WebAuthnProperties dev = new WebAuthnProperties(
                "localhost", "http://localhost:63342");
        assertTrue(dev.isConfigured());
        assertEquals(Set.of("http://localhost:63342"), dev.getAllowedOrigins());
    }

    @Test
    void originsWithPathsQueriesOrFragmentsAreFatal() {
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph",
                        "https://roadwise.example.gov.ph/app"));
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph",
                        "https://roadwise.example.gov.ph?x=1"));
        assertThrows(IllegalStateException.class,
                () -> new WebAuthnProperties("roadwise.example.gov.ph",
                        "https://roadwise.example.gov.ph#frag"));
    }

    @Test
    void originsAreNormalisedTheWayBrowsersReportThem() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "roadwise.example.gov.ph",
                "https://Roadwise.Example.gov.ph:443/, http://127.0.0.1:63342");
        assertEquals(Set.of(
                        "https://roadwise.example.gov.ph",
                        "http://127.0.0.1:63342"),
                properties.getAllowedOrigins());
    }
}
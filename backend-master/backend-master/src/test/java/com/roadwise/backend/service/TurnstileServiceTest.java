package com.roadwise.backend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decision table of the login bot check against a local stub instead of
 * Cloudflare: the point of these tests is what the backend does with a verdict, which must
 * not depend on a network round trip to challenges.cloudflare.com.
 *
 * <p>The failure cases are the important ones. Each of them has to come back as a refusal --
 * a missing token, an unconfigured secret, a rejection, an unreachable endpoint and a token
 * earned somewhere else -- because "could not check" is the cheapest way to disable a bot
 * check, and the login handler only refuses when this service says so.</p>
 */
class TurnstileServiceTest {

    private HttpServer server;
    private TurnstileService service;
    private volatile String lastRequestBody;
    private volatile String nextResponseBody;
    private volatile int nextStatus;

    @BeforeEach
    void startStub() throws IOException {
        lastRequestBody = null;
        nextStatus = 200;
        nextResponseBody = "{\"success\":true,\"hostname\":\"roadwise.example\",\"action\":\"login\"}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/turnstile/v0/siteverify", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload = nextResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        service = new TurnstileService();
        ReflectionTestUtils.setField(service, "secretKey", "test-secret");
        ReflectionTestUtils.setField(service, "verifyUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/turnstile/v0/siteverify");
        ReflectionTestUtils.setField(service, "expectedHostname", "roadwise.example");
        ReflectionTestUtils.setField(service, "expectedAction", "login");
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsTokenThatVerifiesForTheExpectedHostAndAction() {
        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertTrue(result.success());
        // The secret and the client address must travel with the token, otherwise Cloudflare
        // cannot bind the challenge to this site and this caller.
        assertTrue(lastRequestBody.contains("secret=test-secret"));
        assertTrue(lastRequestBody.contains("response=widget-token"));
        assertTrue(lastRequestBody.contains("remoteip=203.0.113.9"));
    }

    @Test
    void refusesWithoutCallingCloudflareWhenTokenIsMissing() {
        TurnstileService.Result result = service.verify(null, "203.0.113.9");

        assertFalse(result.success());
        assertEquals("missing-input-response", result.reason());
        assertEquals(null, lastRequestBody, "a missing token must not cost a round trip");
    }

    @Test
    void refusesWhenTheSecretIsNotConfigured() {
        ReflectionTestUtils.setField(service, "secretKey", "");

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("not-configured", result.reason());
        assertEquals(null, lastRequestBody);
    }

    @Test
    void surfacesCloudflaresErrorCodesWhenTheTokenIsRejected() {
        nextResponseBody = "{\"success\":false,\"error-codes\":[\"invalid-input-response\",\"timeout-or-duplicate\"]}";

        TurnstileService.Result result = service.verify("replayed-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("invalid-input-response,timeout-or-duplicate", result.reason());
    }

    @Test
    void refusesWhenTheTokenWasSolvedOnAnotherHostname() {
        nextResponseBody = "{\"success\":true,\"hostname\":\"someone-elses-site.example\",\"action\":\"login\"}";

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("hostname-mismatch", result.reason());
    }

    @Test
    void refusesWhenTheTokenCarriesNoHostnameButOneIsExpected() {
        nextResponseBody = "{\"success\":true,\"action\":\"login\"}";

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("hostname-mismatch", result.reason());
    }

    @Test
    void refusesWhenTheTokenWasMintedForAnotherAction() {
        nextResponseBody = "{\"success\":true,\"hostname\":\"roadwise.example\",\"action\":\"password-reset\"}";

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("action-mismatch", result.reason());
    }

    @Test
    void refusesWhenTheTokenReportsNoActionButOneIsExpected() {
        nextResponseBody = "{\"success\":true,\"hostname\":\"roadwise.example\"}";

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("action-mismatch", result.reason());
    }

    @Test
    void skipsTheHostnameAndActionChecksWhenTheyAreNotConfigured() {
        ReflectionTestUtils.setField(service, "expectedHostname", null);
        ReflectionTestUtils.setField(service, "expectedAction", null);
        nextResponseBody = "{\"success\":true}";

        assertTrue(service.verify("widget-token", "203.0.113.9").success());
    }

    @Test
    void refusesWhenCloudflareCannotBeReached() {
        // Port 1 on loopback refuses the connection immediately: this is the "the check
        // cannot run" case, which must not be mistaken for "the check passed".
        ReflectionTestUtils.setField(service, "verifyUrl", "http://127.0.0.1:1/turnstile/v0/siteverify");

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
        assertEquals("verification-unavailable", result.reason());
    }

    @Test
    void refusesOnAnEmptyVerificationBody() {
        nextStatus = 200;
        nextResponseBody = "";

        TurnstileService.Result result = service.verify("widget-token", "203.0.113.9");

        assertFalse(result.success());
    }
}

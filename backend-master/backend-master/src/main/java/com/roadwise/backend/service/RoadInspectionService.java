package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for the RoadWise FastAPI inference service.
 *
 * Safety policy implemented here:
 *  - An empty or invalid image NEVER yields a fabricated severity. It returns
 *    severity "Unassessed", confidence 0 and an explicit status that requires
 *    human review.
 *  - If the AI service is unreachable, returns HTTP errors, or sends back an
 *    unusable payload, the result is an explicit failure state
 *    (AI_UNAVAILABLE / AI_ERROR) - never a normal-looking "Medium" fallback,
 *    because a technical outage must not masquerade as a valid prediction.
 *  - Requests are authenticated with a shared service API key
 *    (X-API-Key header); the AI endpoint is fail-closed without it.
 *  - A simple circuit breaker prevents hammering a degraded AI service.
 */
@Service
public class RoadInspectionService {

    public static final String SEVERITY_UNASSESSED = "Unassessed";

    /** Statuses that may legitimately come back from the hardened AI service. */
    private static final Set<String> KNOWN_STATUSES = Set.of(
            "ROAD_CONFIRMED", "CONFIRMED", "NON_ROAD", "NON_ROAD_OR_UNCERTAIN",
            "LOW_IMAGE_QUALITY", "UNCERTAIN_PREDICTION", "MODEL_ERROR",
            "AI_UNAVAILABLE", "REVIEW_REQUIRED", "INVALID_INPUT");

    @Value("${ai.service.url:https://roadwise-ai-service.onrender.com}")
    private String aiServiceUrl;

    /** Shared secret for service-to-service auth to the inference API. */
    @Value("${ai.service.api-key:}")
    private String aiServiceApiKey;

    private final RestTemplate restTemplate;

    // --- Minimal circuit breaker -------------------------------------------
    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_WINDOW_MS = 30_000L;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0L);

    public RoadInspectionService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    public static class InspectionResult {
        private final String severity;
        private final double confidence;
        private final String status;
        private final boolean reviewRequired;
        private final Double roadConfidence;
        private final String message;

        public InspectionResult(String severity, double confidence) {
            this(severity, confidence, "CONFIRMED", false, null, null);
        }

        public InspectionResult(String severity, double confidence, String status,
                                boolean reviewRequired, Double roadConfidence, String message) {
            this.severity = severity;
            this.confidence = confidence;
            this.status = status;
            this.reviewRequired = reviewRequired;
            this.roadConfidence = roadConfidence;
            this.message = message;
        }

        /** Explicit failure/review state: never pretend to have a prediction. */
        public static InspectionResult unassessed(String status, String message) {
            return new InspectionResult(SEVERITY_UNASSESSED, 0.0, status, true, null, message);
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
        public String getStatus() { return status; }
        public boolean isReviewRequired() { return reviewRequired; }
        public Double getRoadConfidence() { return roadConfidence; }
        public String getMessage() { return message; }
    }

    public InspectionResult analyzeSeverity(MultipartFile file) {
        // 1. Empty / missing uploads are INVALID INPUT, not "Low severity".
        if (file == null || file.isEmpty()) {
            return InspectionResult.unassessed("INVALID_INPUT",
                    "No image was provided; report requires manual review.");
        }

        // 2. Circuit breaker: fail fast while the AI service is degraded.
        if (isCircuitOpen()) {
            return InspectionResult.unassessed("AI_UNAVAILABLE",
                    "AI service temporarily unavailable (circuit open); manual review required.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            // 3. Service-to-service authentication.
            if (aiServiceApiKey != null && !aiServiceApiKey.isBlank()) {
                headers.set("X-API-Key", aiServiceApiKey);
            }

            byte[] bytes = file.getBytes();
            ByteArrayResource fileResource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    // Never trust the client filename for anything but transport.
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "damage_image.jpg";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response;
            try {
                response = restTemplate.postForEntity(
                        aiServiceUrl + "/predict", requestEntity, Map.class);
            } catch (HttpStatusCodeException hce) {
                // 4xx from the AI service is a VALIDATED rejection (invalid input,
                // bad quality, non-road, uncertain...). Surface it as Unassessed +
                // reviewRequired; never invent a severity.
                recordFailure();
                String status = mapRejectionStatus(hce.getRawStatusCode());
                System.err.println(">>> [AI REJECTION] HTTP " + hce.getRawStatusCode());
                return InspectionResult.unassessed(status,
                        "Image was rejected by the AI validation pipeline; manual review required.");
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respBody = response.getBody();
                String severity = respBody.getOrDefault("severity", SEVERITY_UNASSESSED).toString();
                double confidence = parseDouble(respBody.get("confidence"), 0.0);
                String status = respBody.getOrDefault("status", "").toString();
                boolean reviewRequired = Boolean.TRUE.equals(respBody.get("reviewRequired"))
                        || SEVERITY_UNASSESSED.equalsIgnoreCase(severity)
                        || !KNOWN_STATUSES.contains(status);
                Double roadConfidence = respBody.get("roadConfidence") != null
                        ? parseDouble(respBody.get("roadConfidence"), 0.0) : null;
                String message = respBody.get("message") != null ? respBody.get("message").toString() : null;

                consecutiveFailures.set(0);
                System.out.println(">>> [AI RESULT] severity=" + severity + " conf=" + confidence
                        + "% status=" + status + " reviewRequired=" + reviewRequired);
                return new InspectionResult(severity, confidence,
                        status.isEmpty() ? "CONFIRMED" : status, reviewRequired, roadConfidence, message);
            }

            // Non-2xx from the AI service is a technical failure, not a prediction.
            recordFailure();
            System.err.println(">>> [AI HTTP ERROR] Microservice returned status: " + response.getStatusCode());
            return InspectionResult.unassessed("AI_UNAVAILABLE",
                    "AI service returned an error status; manual review required.");

        } catch (Exception e) {
            recordFailure();
            System.err.println(">>> [AI CLIENT ERROR] Microservice communication failed: " + e.getMessage());
            return InspectionResult.unassessed("AI_UNAVAILABLE",
                    "AI service could not be reached; manual review required.");
        }
    }

    private static String mapRejectionStatus(int httpStatus) {
        switch (httpStatus) {
            case 400: return "INVALID_INPUT";
            case 413: return "INVALID_INPUT";
            case 401:
            case 403: return "AI_UNAVAILABLE";
            case 429:
            case 503: return "AI_UNAVAILABLE";
            default:  return "AI_ERROR";
        }
    }

    private boolean isCircuitOpen() {
        if (consecutiveFailures.get() < FAILURE_THRESHOLD) {
            return false;
        }
        long openedAt = circuitOpenedAt.get();
        if (System.currentTimeMillis() - openedAt > OPEN_WINDOW_MS) {
            // Half-open: allow one probe attempt through.
            circuitOpenedAt.set(System.currentTimeMillis());
            consecutiveFailures.set(FAILURE_THRESHOLD - 1);
            return false;
        }
        return true;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitOpenedAt.compareAndSet(0L, System.currentTimeMillis());
            circuitOpenedAt.set(System.currentTimeMillis());
        }
    }

    private static double parseDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(value.toString());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

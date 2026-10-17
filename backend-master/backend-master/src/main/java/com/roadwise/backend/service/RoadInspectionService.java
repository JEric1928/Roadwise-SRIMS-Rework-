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
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", asResource(file));
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response;
            try {
                response = restTemplate.exchange(
                        aiServiceUrl + "/predict", HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (HttpStatusCodeException hce) {
                // 4xx from the AI service is a VALIDATED rejection (invalid input,
                // bad quality, non-road, uncertain...). Surface it as Unassessed +
                // reviewRequired; never invent a severity.
                recordFailure();
                String status = mapRejectionStatus(hce.getStatusCode().value());
                System.err.println(">>> [AI REJECTION] HTTP " + hce.getStatusCode().value());
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

    // =====================================================================
    // Cascaded multi-image pipeline (Stage 1 gate + Stage 2 max aggregation)
    // =====================================================================

    /** Stage-1 rejection message, fixed by the platform specification. */
    public static final String GATE_REJECTION_MESSAGE =
            "Invalid Upload: Image does not contain recognizable road distress.";

    /** One report carries at most three photos: close-up, wide angle, perpendicular. */
    public static final int MAX_REPORT_IMAGES = 3;

    /** A photo the Stage-1 guardrail refused, with enough detail to retake it. */
    public record RejectedImage(int index, String perspective, String severity, String status,
                                Double pMax, Double entropy, String reason) {
    }

    /**
     * Outcome of the cascaded multi-image pipeline.
     *
     * <p>{@code accepted == false} is not necessarily a failure: it is the Stage-1
     * guardrail doing its job. Callers must distinguish the two cases —
     * {@link #isValidationRejection()} maps to HTTP 422 (the submission is invalid
     * and the user must retake a photo), while {@link #isTransportFailure()} maps to
     * 503 (the inference service is down and the report may still be saved with an
     * unassessed severity).</p>
     */
    public static final class MultiImageInspectionResult {

        private static final String STATUS_VALIDATION_REJECTED = "VALIDATION_REJECTED";

        private final boolean accepted;
        private final String aggregatedSeverity;
        private final int aggregatedSeverityScore;
        private final double confidence;
        private final double meanConfidence;
        private final String status;
        private final String message;
        private final List<RejectedImage> rejected;

        private MultiImageInspectionResult(boolean accepted, String aggregatedSeverity,
                                           int aggregatedSeverityScore, double confidence,
                                           double meanConfidence, String status, String message,
                                           List<RejectedImage> rejected) {
            this.accepted = accepted;
            this.aggregatedSeverity = aggregatedSeverity;
            this.aggregatedSeverityScore = aggregatedSeverityScore;
            this.confidence = confidence;
            this.meanConfidence = meanConfidence;
            this.status = status;
            this.message = message;
            this.rejected = rejected == null ? Collections.emptyList() : List.copyOf(rejected);
        }

        static MultiImageInspectionResult accepted(String severity, int score,
                                                   double confidence, double meanConfidence) {
            return new MultiImageInspectionResult(true, severity, score, confidence, meanConfidence,
                    "CONFIRMED", null, Collections.emptyList());
        }

        static MultiImageInspectionResult rejected(List<RejectedImage> rejected) {
            return new MultiImageInspectionResult(false, SEVERITY_UNASSESSED, 0, 0.0, 0.0,
                    STATUS_VALIDATION_REJECTED, GATE_REJECTION_MESSAGE, rejected);
        }

        static MultiImageInspectionResult failed(String status, String message) {
            return new MultiImageInspectionResult(false, SEVERITY_UNASSESSED, 0, 0.0, 0.0,
                    status, message, Collections.emptyList());
        }

        public boolean isAccepted() { return accepted; }

        /** True when the submission must be refused with 422 rather than stored. */
        public boolean isValidationRejection() {
            return !accepted && STATUS_VALIDATION_REJECTED.equals(status);
        }

        /** True when the AI service itself could not be reached or errored. */
        public boolean isTransportFailure() {
            return !accepted && !STATUS_VALIDATION_REJECTED.equals(status);
        }

        public String getAggregatedSeverity() { return aggregatedSeverity; }
        public int getAggregatedSeverityScore() { return aggregatedSeverityScore; }
        public double getConfidence() { return confidence; }
        public double getMeanConfidence() { return meanConfidence; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
        public List<RejectedImage> getRejected() { return rejected; }
    }

    /**
     * Run Stage-1 validation then Stage-2 max aggregation over one report's photos.
     *
     * <p>The max rule is deliberate: a report is as severe as its worst visible
     * damage, so a single high-severity frame must not be averaged away by two mild
     * ones. It also means a bad frame cannot be tolerated — which is why Stage 1
     * rejects the whole submission rather than dropping the offending image.</p>
     *
     * <p>Never throws for a rejected or unreachable service; callers branch on the
     * returned status. That keeps the upload endpoint's error handling in one place
     * instead of scattered try/catch.</p>
     */
    public MultiImageInspectionResult analyzeMultiImage(List<MultipartFile> files,
                                                        List<String> perspectives) {
        if (files == null || files.isEmpty()) {
            return MultiImageInspectionResult.failed("INVALID_INPUT",
                    "At least one image is required.");
        }
        if (files.size() > MAX_REPORT_IMAGES) {
            return MultiImageInspectionResult.failed("INVALID_INPUT",
                    "At most " + MAX_REPORT_IMAGES + " images may be submitted per report.");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                return MultiImageInspectionResult.failed("INVALID_INPUT",
                        "One of the uploaded images was empty; upload 1-3 photos and retry.");
            }
        }
        if (isCircuitOpen()) {
            return MultiImageInspectionResult.failed("AI_UNAVAILABLE",
                    "AI service temporarily unavailable (circuit open); manual review required.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (aiServiceApiKey != null && !aiServiceApiKey.isBlank()) {
                headers.set("X-API-Key", aiServiceApiKey);
            }

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            for (MultipartFile file : files) {
                body.add("files", asResource(file));
            }
            if (perspectives != null) {
                for (String perspective : perspectives) {
                    if (perspective != null && !perspective.isBlank()) {
                        body.add("perspectives", perspective);
                    }
                }
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response;
            try {
                response = restTemplate.exchange(
                        aiServiceUrl + "/predict-multi", HttpMethod.POST, requestEntity,
                        new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (HttpStatusCodeException hce) {
                int code = hce.getStatusCode().value();
                if (code == 422) {
                    // Stage 1 refused at least one photo. This is an expected
                    // outcome, not a service degradation, so the circuit breaker
                    // is left alone.
                    System.err.println(">>> [AI GATE] Stage-1 rejection HTTP 422");
                    return MultiImageInspectionResult.rejected(parseRejectedImages(hce));
                }
                recordFailure();
                System.err.println(">>> [AI MULTI ERROR] HTTP " + code);
                return MultiImageInspectionResult.failed(mapRejectionStatus(code),
                        "AI service rejected the multi-image request (HTTP " + code + ").");
            }

            Map<String, Object> respBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || respBody == null) {
                recordFailure();
                return MultiImageInspectionResult.failed("AI_UNAVAILABLE",
                        "AI service returned an error status; manual review required.");
            }

            int score = asInt(respBody.get("aggregatedSeverityScore"), 0);
            String severity = stringOrNull(respBody.get("aggregatedSeverity"));
            if (score < 1 || severity == null) {
                // Defence in depth: a 2xx that carries no usable class must not be
                // persisted as severity 0 or silently promoted.
                recordFailure();
                return MultiImageInspectionResult.failed("AI_ERROR",
                        "AI service returned an unusable aggregate; manual review required.");
            }

            consecutiveFailures.set(0);
            String aggregated = severity;
            double confidence = parseDouble(respBody.get("confidence"), 0.0);
            double meanConfidence = parseDouble(respBody.get("meanConfidence"), confidence);
            System.out.println(">>> [AI MULTI] images=" + files.size() + " severity=" + aggregated
                    + " score=" + score + " conf=" + confidence + "%");
            return MultiImageInspectionResult.accepted(aggregated, score, confidence, meanConfidence);

        } catch (Exception e) {
            recordFailure();
            System.err.println(">>> [AI MULTI EXCEPTION] " + e.getMessage());
            return MultiImageInspectionResult.failed("AI_UNAVAILABLE",
                    "AI service could not be reached; manual review required.");
        }
    }

    /**
     * Wrap an upload for transport, discarding the client filename's path.
     *
     * <p>Shared by the single- and multi-image paths so the "never trust the client
     * filename" rule is enforced in exactly one place.</p>
     */
    private ByteArrayResource asResource(MultipartFile file) throws java.io.IOException {
        byte[] bytes = file.getBytes();
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename() != null ? file.getOriginalFilename() : "damage_image.jpg";
            }
        };
    }

    /**
     * Extract the per-image rejections from a Stage-1 422 body.
     *
     * <p>Failure here is tolerated: the rejection message is contractual, the detail
     * list is not. An unparseable body still yields an empty detail list plus the
     * fixed message, which is enough for the caller to refuse the submission.</p>
     */
    private static List<RejectedImage> parseRejectedImages(HttpStatusCodeException hce) {
        List<RejectedImage> rejected = new ArrayList<>();
        try {
            Map<?, ?> body = hce.getResponseBodyAs(Map.class);
            if (body != null && body.get("detail") instanceof Map<?, ?> detail
                    && detail.get("rejected") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> entry) {
                        rejected.add(new RejectedImage(
                                asInt(entry.get("index"), -1),
                                stringOrNull(entry.get("perspective")),
                                stringOrNull(entry.get("severity")),
                                stringOrNull(entry.get("status")),
                                nullableDouble(entry.get("pMax")),
                                nullableDouble(entry.get("entropy")),
                                stringOrNull(entry.get("reason"))));
                    }
                }
            }
        } catch (Exception ignored) {
            // Detail list is best-effort; GATE_REJECTION_MESSAGE carries the contract.
        }
        return rejected;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Double nullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.toString());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

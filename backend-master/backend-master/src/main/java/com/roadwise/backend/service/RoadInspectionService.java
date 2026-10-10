package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
public class RoadInspectionService {

    @Value("${ai.service.url:https://roadwise-ai-service.onrender.com}")
    private String aiServiceUrl;

    @Value("${ai.service.api.key:roadwise-internal-secret-key}")
    private String aiServiceApiKey;

    private final RestTemplate restTemplate;

    public RoadInspectionService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public static class InspectionResult {
        private final String severity;
        private final double confidence;
        private final String status;
        private final boolean reviewRequired;

        public InspectionResult(String severity, double confidence) {
            this(severity, confidence, "CONFIRMED", false);
        }

        public InspectionResult(String severity, double confidence, String status, boolean reviewRequired) {
            this.severity = severity;
            this.confidence = confidence;
            this.status = status;
            this.reviewRequired = reviewRequired;
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
        public String getStatus() { return status; }
        public boolean isReviewRequired() { return reviewRequired; }
    }

    public InspectionResult analyzeSeverity(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new InspectionResult("Unassessed", 0.0, "INVALID_INPUT", true);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (aiServiceApiKey != null && !aiServiceApiKey.trim().isEmpty()) {
                headers.set("X-API-Key", aiServiceApiKey);
            }

            // Wrap file bytes in ByteArrayResource and preserve original filename
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "damage_image.jpg";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    aiServiceUrl + "/predict",
                    requestEntity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respBody = response.getBody();
                String severity = respBody.getOrDefault("severity", "Unassessed").toString();
                double confidence = Double.parseDouble(respBody.getOrDefault("confidence", 0.0).toString());
                String status = respBody.getOrDefault("status", "CONFIRMED").toString();
                boolean reviewRequired = Boolean.parseBoolean(respBody.getOrDefault("reviewRequired", false).toString());

                System.out.println(">>> [AI SUCCESS] Predicted: " + severity + " (" + confidence + "%, Status: " + status + ")");
                return new InspectionResult(severity, confidence, status, reviewRequired);
            } else {
                System.err.println(">>> [AI HTTP ERROR] Microservice returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println(">>> [AI CLIENT ERROR] Microservice communication failed: " + e.getMessage());
        }

        // Safe failure state if the microservice is unavailable or errors out
        return new InspectionResult("Unassessed", 0.0, "AI_UNAVAILABLE", true);
    }
}

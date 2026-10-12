package com.roadwise.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/forecasting")
public class ForecastingOrchestratorController {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${ai.service.api-key:}")
    private String aiServiceApiKey;

    @PostMapping("/pavement-decay")
    public ResponseEntity<?> getPavementDecay(@RequestBody Map<String, Object> request) {
        return forwardRequest("/api/v1/forecasting/pavement-decay", request);
    }

    @PostMapping("/hydrological-shock")
    public ResponseEntity<?> getHydrologicalShock(@RequestBody Map<String, Object> request) {
        return forwardRequest("/api/v1/forecasting/hydrological-shock", request);
    }

    @PostMapping("/defect-propagation")
    public ResponseEntity<?> getDefectPropagation(@RequestBody Map<String, Object> request) {
        return forwardRequest("/api/v1/forecasting/defect-propagation", request);
    }

    @PostMapping("/financial-var")
    public ResponseEntity<?> getFinancialRisk(@RequestBody Map<String, Object> request) {
        return forwardRequest("/api/v1/forecasting/financial-var", request);
    }

    @PostMapping("/data-assimilation")
    public ResponseEntity<?> getDataAssimilation(@RequestBody Map<String, Object> request) {
        return forwardRequest("/api/v1/forecasting/data-assimilation", request);
    }

    private ResponseEntity<?> forwardRequest(String path, Map<String, Object> request) {
        try {
            String url = aiServiceUrl + path;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Service-to-service authentication; the AI service is fail-closed
            // without a matching X-API-Key.
            if (aiServiceApiKey != null && !aiServiceApiKey.isBlank()) {
                headers.set("X-API-Key", aiServiceApiKey);
            }
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            return restTemplate.postForEntity(url, entity, Map.class);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Forecasting Service Orchestration Exception",
                    "details", e.getMessage()
            ));
        }
    }
}

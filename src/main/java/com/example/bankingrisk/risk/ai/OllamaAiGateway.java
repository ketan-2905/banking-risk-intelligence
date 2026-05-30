package com.example.bankingrisk.risk.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

public class OllamaAiGateway implements SpringAiGateway {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Duration timeout;
    private final boolean enabled;

    public OllamaAiGateway(
            WebClient webClient,
            ObjectMapper objectMapper,
            String model,
            Duration timeout,
            boolean enabled) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @Override
    public String generateRiskNarrative(String maskedPrompt) {
        if (!enabled) {
            return null;
        }

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "prompt", maskedPrompt,
            "stream", false
        );

        return webClient.post()
            .uri("/api/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(timeout)
            .map(this::extractResponse)
            .onErrorMap(ex -> new RuntimeException("LLM_CALL_FAILED: " + ex.getMessage(), ex))
            .block();
    }

    private String extractResponse(String json) {
        try {
            return objectMapper.readTree(json).path("response").asText();
        } catch (Exception ex) {
            throw new RuntimeException("LLM_PARSE_FAILED: " + ex.getMessage(), ex);
        }
    }
}

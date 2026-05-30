package com.example.bankingrisk.config;

import com.example.bankingrisk.risk.ai.OllamaAiGateway;
import com.example.bankingrisk.risk.ai.SpringAiGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class OllamaAiGatewayConfig {

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ai.ollama.model:llama3}")
    private String model;

    @Value("${ai.ollama.enabled:false}")
    private boolean enabled;

    @Bean
    @ConditionalOnMissingBean(SpringAiGateway.class)
    public SpringAiGateway ollamaAiGateway(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        WebClient webClient = webClientBuilder
            .baseUrl(baseUrl)
            .build();
        return new OllamaAiGateway(webClient, objectMapper, model, Duration.ofSeconds(5), enabled);
    }
}

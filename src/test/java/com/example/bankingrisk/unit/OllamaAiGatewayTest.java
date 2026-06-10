package com.example.bankingrisk.unit;

import com.example.bankingrisk.risk.ai.OllamaAiGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaAiGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OllamaAiGateway gatewayWith(WebClient client) {
        return new OllamaAiGateway(client, MAPPER, "llama3", Duration.ofSeconds(5), true);
    }

    @Test
    void successful_response_parsed() {
        String json = "{\"model\":\"llama3\",\"response\":\"AI narrative content\",\"done\":true}";
        WebClient mockClient = WebClient.builder()
            .exchangeFunction(req -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build()
            ))
            .build();

        String result = gatewayWith(mockClient).generateRiskNarrative("test prompt");

        assertThat(result).isEqualTo("AI narrative content");
    }

    @Test
    void timeout_handled() {
        WebClient mockClient = WebClient.builder()
            .exchangeFunction(req -> Mono.never())
            .build();

        OllamaAiGateway gateway = new OllamaAiGateway(
            mockClient, MAPPER, "llama3", Duration.ofMillis(100), true);

        assertThatThrownBy(() -> gateway.generateRiskNarrative("test prompt"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void http_error_handled() {
        WebClient mockClient = WebClient.builder()
            .exchangeFunction(req -> Mono.just(
                ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body("{\"error\":\"model not found\"}")
                    .build()
            ))
            .build();

        assertThatThrownBy(() -> gatewayWith(mockClient).generateRiskNarrative("test prompt"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void disabled_gateway_returns_null() {
        WebClient client = WebClient.builder().build();
        OllamaAiGateway gateway = new OllamaAiGateway(
            client, MAPPER, "llama3", Duration.ofSeconds(5), false);

        assertThat(gateway.generateRiskNarrative("test prompt")).isNull();
    }
}

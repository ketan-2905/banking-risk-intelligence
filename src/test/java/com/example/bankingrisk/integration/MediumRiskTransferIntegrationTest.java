package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.event.TransferRiskEvaluatedEvent;
import com.example.bankingrisk.risk.model.RiskLevel;
import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class MediumRiskTransferIntegrationTest {

    @TestConfiguration
    static class EventCapture {
        final List<TransferRiskEvaluatedEvent> captured = new CopyOnWriteArrayList<>();

        @EventListener
        void onEvent(TransferRiskEvaluatedEvent event) {
            captured.add(event);
        }

        void reset() {
            captured.clear();
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banking_risk_test")
            .withUsername("banking")
            .withPassword("test");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired AccountRepository accountRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired RiskAlertRepository riskAlertRepository;
    @Autowired BeneficiaryRepository beneficiaryRepository;
    @Autowired LoginAuditRepository loginAuditRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired EventCapture eventCapture;

    private UUID userId;
    private UUID sourceAccountId;
    private UUID destAccountId;

    @BeforeEach
    void setup() {
        eventCapture.reset();

        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        riskAlertRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        beneficiaryRepository.deleteAll();
        loginAuditRepository.deleteAll();
        accountRepository.deleteAll();

        userId = UUID.randomUUID();

        Account src = new Account();
        src.setOwnerUserId(userId);
        src.setAccountNumber("MED-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(100_000_00L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("MED-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // No beneficiary pre-registration → NEW_BENEFICIARY (+25) + ACCOUNT_AGE (+20) = 45 → MEDIUM
    }

    @Test
    void mediumRiskTransfer_posts_andCreatesAlert() {
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, 1_000_00L, "USD");
        ResponseEntity<CreateTransferResponse> resp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("POSTED");
        assertThat(resp.getBody().riskLevel()).isEqualTo("MEDIUM");

        // Risk alert must be created
        var alerts = riskAlertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getRiskLevel().name()).isEqualTo("MEDIUM");
        assertThat(alerts.get(0).getRiskScore()).isGreaterThanOrEqualTo(40);

        // Funds settled normally for MEDIUM risk
        Account src = accountRepository.findById(sourceAccountId).orElseThrow();
        Account dst = accountRepository.findById(destAccountId).orElseThrow();
        assertThat(src.getHeldBalanceMinor()).isEqualTo(0L);
        assertThat(dst.getAvailableBalanceMinor()).isEqualTo(1_000_00L);
    }

    @Test
    void mediumRiskTransfer_publishesEventForAi() {
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, 1_000_00L, "USD");
        restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        // EventCapture uses a plain @EventListener so it fires on the Tomcat thread synchronously
        // before the HTTP response is sent — by the time exchange() returns the event is captured.
        assertThat(eventCapture.captured).hasSize(1);
        TransferRiskEvaluatedEvent event = eventCapture.captured.get(0);
        assertThat(event.evaluation().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(event.context().ownerUserId()).isEqualTo(userId);
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", idempotencyKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

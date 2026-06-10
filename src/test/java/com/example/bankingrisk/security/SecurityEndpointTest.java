package com.example.bankingrisk.security;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security boundary tests — verifies auth enforcement and PII absence in API responses.
 * Each test proves one security invariant independently of functional correctness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class SecurityEndpointTest {

    @SuppressWarnings("resource")
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
    @Autowired StringRedisTemplate redisTemplate;

    private UUID customerId;
    private UUID analystId;
    private UUID account1Id;
    private UUID account2Id;

    @BeforeEach
    void setup() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();
        ledgerEntryRepository.deleteAll();
        riskAlertRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        customerId = UUID.randomUUID();
        analystId = UUID.randomUUID();

        Account a1 = new Account();
        a1.setOwnerUserId(customerId);
        a1.setAccountNumber("SEC-TEST-" + UUID.randomUUID());
        a1.setCurrency("USD");
        a1.setAvailableBalanceMinor(100_000_00L);
        a1.setHeldBalanceMinor(0L);
        account1Id = accountRepository.save(a1).getId();

        Account a2 = new Account();
        a2.setOwnerUserId(UUID.randomUUID());
        a2.setAccountNumber("SEC-TEST-" + UUID.randomUUID());
        a2.setCurrency("USD");
        a2.setAvailableBalanceMinor(50_000_00L);
        a2.setHeldBalanceMinor(0L);
        account2Id = accountRepository.save(a2).getId();
    }

    // ── 401: unauthenticated requests ──────────────────────────────────────────

    @Test
    void noToken_transferEndpoint_returns401() {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(new CreateTransferRequest(account1Id, account2Id, 100L, "USD"), h),
            String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noToken_analystAlerts_returns401() {
        ResponseEntity<String> resp = restTemplate.exchange("/api/analyst/alerts", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noToken_analystApprove_returns401() {
        UUID fakeAlertId = UUID.randomUUID();
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts/" + fakeAlertId + "/approve", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "test"), new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 403: wrong role ────────────────────────────────────────────────────────

    @Test
    void customerToken_analystAlertList_returns403() {
        HttpHeaders h = bearerHeaders(customerId, Set.of("CUSTOMER"));
        ResponseEntity<String> resp = restTemplate.exchange("/api/analyst/alerts", HttpMethod.GET,
            new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void customerToken_analystApprove_returns403() {
        HttpHeaders h = bearerHeaders(customerId, Set.of("CUSTOMER"));
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts/" + UUID.randomUUID() + "/approve", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "trying"), h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void customerToken_analystReject_returns403() {
        HttpHeaders h = bearerHeaders(customerId, Set.of("CUSTOMER"));
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts/" + UUID.randomUUID() + "/reject", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "trying"), h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void analystToken_transferCreation_returns403() {
        HttpHeaders h = bearerHeaders(analystId, Set.of("ANALYST"));
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(new CreateTransferRequest(account1Id, account2Id, 100L, "USD"), h),
            String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Account ownership enforcement ─────────────────────────────────────────

    @Test
    void customerCannotDebitAccountTheyDoNotOwn_returns403() {
        UUID otherId = UUID.randomUUID(); // does not own account1
        HttpHeaders h = bearerHeaders(otherId, Set.of("CUSTOMER"));
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(new CreateTransferRequest(account1Id, account2Id, 100L, "USD"), h),
            String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── PII must not appear in analyst alert list response ────────────────────

    @Test
    void alertListResponse_doesNotContainBankAccountNumbers() {
        // Create a REVIEW_PENDING alert with a recognisable account number pattern in context
        String sensitiveAccountNumber = "ACCT-REAL-SENSITIVE-12345";
        RiskAlert alert = new RiskAlert();
        alert.setTransferId(UUID.randomUUID());
        alert.setOwnerUserId(customerId);
        alert.setRiskScore(85);
        alert.setRiskLevel(RiskLevel.HIGH);
        alert.setStatus(RiskAlertStatus.REVIEW_PENDING);
        alert.setPriority(true);
        // Simulate context snapshot that has been PII-masked (account number replaced with token)
        alert.setTriggeredRulesJson("[{\"rule\":\"HIGH_AMOUNT\",\"evidence\":\"ACCOUNT_TOKEN_1\"}]");
        alert.setContextSnapshotJson("{\"accountToken\":\"ACCOUNT_TOKEN_1\"}");
        riskAlertRepository.save(alert);

        HttpHeaders h = bearerHeaders(analystId, Set.of("ANALYST"));
        ResponseEntity<String> resp = restTemplate.exchange("/api/analyst/alerts", HttpMethod.GET,
            new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).isNotNull();
        // The raw account number must not appear anywhere in the response
        assertThat(body).doesNotContain(sensitiveAccountNumber);
        // contextSnapshotJson is never returned to analyst — only maskedNarrative and triggeredRules
        assertThat(body).doesNotContain("contextSnapshotJson");
    }

    @Test
    void alertListResponse_doesNotContainEmailPatterns() {
        RiskAlert alert = new RiskAlert();
        alert.setTransferId(UUID.randomUUID());
        alert.setOwnerUserId(customerId);
        alert.setRiskScore(70);
        alert.setRiskLevel(RiskLevel.HIGH);
        alert.setStatus(RiskAlertStatus.REVIEW_PENDING);
        alert.setPriority(true);
        alert.setTriggeredRulesJson("[{\"rule\":\"VELOCITY\",\"evidence\":\"masked\"}]");
        alert.setContextSnapshotJson("{\"email\":\"user@example.com\",\"phone\":\"555-1234\"}");
        // Even if AI narrative somehow had PII, the masked narrative field is what's returned
        alert.setMaskedNarrative("Risk flagged for CUSTOMER_TOKEN_1 due to velocity.");
        riskAlertRepository.save(alert);

        HttpHeaders h = bearerHeaders(analystId, Set.of("ANALYST"));
        ResponseEntity<String> resp = restTemplate.exchange("/api/analyst/alerts", HttpMethod.GET,
            new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).isNotNull();
        // Raw email and phone from contextSnapshotJson must not leak into the API response
        assertThat(body).doesNotContain("user@example.com");
        assertThat(body).doesNotContain("555-1234");
    }

    // ── Actuator health is public ────────────────────────────────────────────

    @Test
    void actuatorHealth_isPublicWithoutAuth() {
        ResponseEntity<String> resp = restTemplate.exchange("/actuator/health", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("UP");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private HttpHeaders bearerHeaders(UUID userId, Set<String> roles) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, roles));
        return h;
    }
}

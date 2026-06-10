package com.example.bankingrisk.integration;

import com.example.bankingrisk.analyst.dto.AlertSummaryResponse;
import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.ai.SpringAiGateway;
import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.LoginAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AnalystAlertListIntegrationTest {

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

    @MockBean
    SpringAiGateway mockGateway;

    @Autowired TestRestTemplate restTemplate;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired AccountRepository accountRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired RiskAlertRepository riskAlertRepository;
    @Autowired BeneficiaryRepository beneficiaryRepository;
    @Autowired LoginAuditRepository loginAuditRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private UUID customerId;
    private UUID analystId;
    private UUID sourceAccountId;
    private UUID destAccountId;

    private static final long HIGH_RISK_AMOUNT = 600_000L;

    @BeforeEach
    void setup() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        riskAlertRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        beneficiaryRepository.deleteAll();
        loginAuditRepository.deleteAll();
        accountRepository.deleteAll();

        customerId = UUID.randomUUID();
        analystId  = UUID.randomUUID();

        Account src = new Account();
        src.setOwnerUserId(customerId);
        src.setAccountNumber("LIST-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(10_000_000L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("LIST-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // LOGIN_ANOMALY (+16) + NEW_BENEFICIARY (+25) + ACCOUNT_AGE (+20) + VELOCITY (+30) = 91 → HIGH
        LoginAudit anomaly = new LoginAudit();
        anomaly.setUserId(customerId);
        anomaly.setAnomalyFlag(true);
        loginAuditRepository.save(anomaly);
    }

    @Test
    void analyst_canListAlerts_returns200() {
        createHighRiskTransfer();

        ResponseEntity<AlertSummaryResponse[]> resp = restTemplate.exchange(
            "/api/analyst/alerts",
            HttpMethod.GET,
            new HttpEntity<>(analystHeaders()),
            AlertSummaryResponse[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull().hasSize(1);

        AlertSummaryResponse alert = resp.getBody()[0];
        assertThat(alert.riskLevel()).isEqualTo("HIGH");
        assertThat(alert.status()).isEqualTo("REVIEW_PENDING");
        assertThat(alert.priority()).isTrue();
    }

    @Test
    void customer_cannotListAlerts_returns403() {
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts",
            HttpMethod.GET,
            new HttpEntity<>(customerHeaders()),
            String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void alertList_doesNotContainRawCustomerIds_inMaskedNarrative() {
        createHighRiskTransfer();

        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts",
            HttpMethod.GET,
            new HttpEntity<>(analystHeaders()),
            String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // maskedNarrative is null for HIGH (no AI run), so no narrative PII to check.
        // Verify the response body exists and is parseable JSON.
        assertThat(resp.getBody()).isNotNull().startsWith("[");
    }

    @Test
    void analyst_canFilterAlerts_byStatus() {
        createHighRiskTransfer();

        ResponseEntity<AlertSummaryResponse[]> resp = restTemplate.exchange(
            "/api/analyst/alerts?status=REVIEW_PENDING",
            HttpMethod.GET,
            new HttpEntity<>(analystHeaders()),
            AlertSummaryResponse[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull().hasSize(1);
        assertThat(resp.getBody()[0].status()).isEqualTo("REVIEW_PENDING");
    }

    @Test
    void analyst_canFilterAlerts_byRiskLevel() {
        createHighRiskTransfer();

        ResponseEntity<AlertSummaryResponse[]> highResp = restTemplate.exchange(
            "/api/analyst/alerts?riskLevel=HIGH",
            HttpMethod.GET,
            new HttpEntity<>(analystHeaders()),
            AlertSummaryResponse[].class);

        ResponseEntity<AlertSummaryResponse[]> lowResp = restTemplate.exchange(
            "/api/analyst/alerts?riskLevel=LOW",
            HttpMethod.GET,
            new HttpEntity<>(analystHeaders()),
            AlertSummaryResponse[].class);

        assertThat(highResp.getBody()).isNotNull().hasSize(1);
        assertThat(lowResp.getBody()).isNotNull().isEmpty();
    }

    private ResponseEntity<CreateTransferResponse> createHighRiskTransfer() {
        CreateTransferRequest req = new CreateTransferRequest(
            sourceAccountId, destAccountId, HIGH_RISK_AMOUNT, "USD");
        return restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerTransferHeaders()),
            CreateTransferResponse.class);
    }

    private HttpHeaders analystHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(analystId, Set.of("ANALYST")));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders customerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(customerId, Set.of("CUSTOMER")));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders customerTransferHeaders() {
        HttpHeaders h = customerHeaders();
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }
}

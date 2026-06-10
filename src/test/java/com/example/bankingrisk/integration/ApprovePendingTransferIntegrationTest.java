package com.example.bankingrisk.integration;

import com.example.bankingrisk.analyst.dto.ReviewDecisionResponse;
import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.RiskReviewAuditRepository;
import com.example.bankingrisk.risk.ai.SpringAiGateway;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.LedgerEntryType;
import com.example.bankingrisk.transaction.model.LoginAudit;
import com.example.bankingrisk.transaction.model.TransferStatus;
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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ApprovePendingTransferIntegrationTest {

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
    @Autowired RiskReviewAuditRepository auditRepository;
    @Autowired BeneficiaryRepository beneficiaryRepository;
    @Autowired LoginAuditRepository loginAuditRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private UUID customerId;
    private UUID analystId;
    private UUID sourceAccountId;
    private UUID destAccountId;

    private static final long TRANSFER_AMOUNT = 600_000L; // $6000 → HIGH risk

    @BeforeEach
    void setup() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        auditRepository.deleteAll();
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
        src.setAccountNumber("APPROVE-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(10_000_000L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("APPROVE-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // LOGIN_ANOMALY + NEW_BENEFICIARY + ACCOUNT_AGE + VELOCITY = 91 → HIGH
        LoginAudit anomaly = new LoginAudit();
        anomaly.setUserId(customerId);
        anomaly.setAnomalyFlag(true);
        loginAuditRepository.save(anomaly);
    }

    @Test
    void approve_settlesHeldFundsToDestination() {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        ResponseEntity<ReviewDecisionResponse> resp = restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Transaction verified — approve."), analystHeaders()),
            ReviewDecisionResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().decision()).isEqualTo("APPROVED");
        assertThat(resp.getBody().alertStatus()).isEqualTo(RiskAlertStatus.APPROVED.name());
        assertThat(resp.getBody().transferStatus()).isEqualTo(TransferStatus.APPROVED_SETTLED.name());
    }

    @Test
    void approve_sourceHeldBalanceBecomesZero() {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Verified."), analystHeaders()),
            ReviewDecisionResponse.class);

        Account src = accountRepository.findById(sourceAccountId).orElseThrow();
        assertThat(src.getHeldBalanceMinor()).isZero();
        // available was already debited at hold time; settleHeld does NOT restore it
        assertThat(src.getAvailableBalanceMinor()).isEqualTo(10_000_000L - TRANSFER_AMOUNT);
    }

    @Test
    void approve_destinationReceivesFunds() {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Verified."), analystHeaders()),
            ReviewDecisionResponse.class);

        Account dst = accountRepository.findById(destAccountId).orElseThrow();
        assertThat(dst.getAvailableBalanceMinor()).isEqualTo(TRANSFER_AMOUNT);
    }

    @Test
    void approve_createsSettlementLedgerEntries() {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Verified."), analystHeaders()),
            ReviewDecisionResponse.class);

        var entries = ledgerEntryRepository.findAll();
        // 1 HOLD_DEBIT (at transfer creation) + 1 HELD_TO_SETTLED_DEBIT + 1 CREDIT_AVAILABLE
        assertThat(entries).hasSize(3);
        assertThat(entries.stream().map(e -> e.getEntryType().name()))
            .contains(LedgerEntryType.HOLD_DEBIT.name(),
                      LedgerEntryType.HELD_TO_SETTLED_DEBIT.name(),
                      LedgerEntryType.CREDIT_AVAILABLE.name());
    }

    @Test
    void approve_createsAuditRow() {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Audit reason here."), analystHeaders()),
            ReviewDecisionResponse.class);

        var audits = auditRepository.findByAlertId(alertId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getDecision()).isEqualTo("APPROVED");
        assertThat(audits.get(0).getReason()).isEqualTo("Audit reason here.");
        assertThat(audits.get(0).getAnalystUserId()).isNotNull();
    }

    private UUID createHighRiskTransferAndGetAlertId() {
        CreateTransferRequest req = new CreateTransferRequest(
            sourceAccountId, destAccountId, TRANSFER_AMOUNT, "USD");
        ResponseEntity<CreateTransferResponse> resp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerTransferHeaders()),
            CreateTransferResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return riskAlertRepository.findAll().get(0).getId();
    }

    private HttpHeaders analystHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(analystId, Set.of("ANALYST")));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders customerTransferHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(customerId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

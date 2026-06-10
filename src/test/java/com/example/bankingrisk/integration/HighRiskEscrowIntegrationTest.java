package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
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
class HighRiskEscrowIntegrationTest {

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

    private UUID userId;
    private UUID sourceAccountId;
    private UUID destAccountId;
    // Amount that pushes hourly velocity over 500,000 cents ($5000)
    private static final long HIGH_RISK_AMOUNT = 600_000L; // $6000

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

        userId = UUID.randomUUID();

        Account src = new Account();
        src.setOwnerUserId(userId);
        src.setAccountNumber("HIGH-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(10_000_000L); // $100,000
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("HIGH-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // Login anomaly: +16 points
        LoginAudit anomaly = new LoginAudit();
        anomaly.setUserId(userId);
        anomaly.setAnomalyFlag(true);
        loginAuditRepository.save(anomaly);

        // No beneficiary → NEW_BENEFICIARY (+25)
        // Account age < 7 days → ACCOUNT_AGE_UNDER_7_DAYS (+20)
        // Amount 600_000 > 500_000 → HOURLY_VELOCITY_OVER_5000 (+30)
        // Login anomaly → LOGIN_ANOMALY (+16)
        // Total = 91 → HIGH
    }

    @Test
    void highRiskTransfer_statusIsPendingReview() {
        ResponseEntity<CreateTransferResponse> resp = makeTransfer();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(TransferStatus.PENDING_REVIEW.name());
        assertThat(resp.getBody().riskLevel()).isEqualTo("HIGH");
        assertThat(resp.getBody().riskScore()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void highRiskTransfer_sourceAvailableDecreases_heldIncreases() {
        makeTransfer();

        Account src = accountRepository.findById(sourceAccountId).orElseThrow();
        assertThat(src.getAvailableBalanceMinor()).isEqualTo(10_000_000L - HIGH_RISK_AMOUNT);
        assertThat(src.getHeldBalanceMinor()).isEqualTo(HIGH_RISK_AMOUNT);
    }

    @Test
    void highRiskTransfer_destinationDoesNotReceiveFunds() {
        makeTransfer();

        Account dst = accountRepository.findById(destAccountId).orElseThrow();
        assertThat(dst.getAvailableBalanceMinor()).isEqualTo(0L);
        assertThat(dst.getHeldBalanceMinor()).isEqualTo(0L);
    }

    @Test
    void highRiskTransfer_exactlyOneHoldLedgerEntry() {
        makeTransfer();

        var entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryType()).isEqualTo(LedgerEntryType.HOLD_DEBIT);
        assertThat(entries.get(0).getAmountMinor()).isEqualTo(HIGH_RISK_AMOUNT);
        assertThat(entries.get(0).getAccountId()).isEqualTo(sourceAccountId);
    }

    @Test
    void highRiskTransfer_alertIsPriority_withReviewPendingStatus() {
        makeTransfer();

        var alerts = riskAlertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).isPriority()).isTrue();
        assertThat(alerts.get(0).getStatus()).isEqualTo(RiskAlertStatus.REVIEW_PENDING);
        assertThat(alerts.get(0).getRiskScore()).isGreaterThanOrEqualTo(80);
        assertThat(alerts.get(0).getTriggeredRulesJson()).contains("ACCOUNT_AGE_UNDER_7_DAYS");
    }

    private ResponseEntity<CreateTransferResponse> makeTransfer() {
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, HIGH_RISK_AMOUNT, "USD");
        return restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", idempotencyKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

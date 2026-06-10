package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AsyncAiAfterCommitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banking_risk_test")
            .withUsername("banking")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
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

    private UUID userId;
    private UUID sourceAccountId;
    private UUID destAccountId;

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
        src.setAccountNumber("ASYNC-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(100_000_00L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("ASYNC-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();
        // No beneficiary pre-registration: NEW_BENEFICIARY (+25) + ACCOUNT_AGE (+20) = 45 → MEDIUM
    }

    @Test
    void mediumRisk_narrativeSavedToAlert_afterCommit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedPrompt = new AtomicReference<>();

        when(mockGateway.generateRiskNarrative(anyString())).thenAnswer(inv -> {
            capturedPrompt.set(inv.getArgument(0));
            latch.countDown();
            return "Risk Summary: Account age under 7 days combined with new beneficiary suggests elevated risk.";
        });

        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, 1_000_00L, "USD");
        ResponseEntity<CreateTransferResponse> resp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().riskLevel()).isEqualTo("MEDIUM");

        // Gateway must be called after transaction commits (verified by latch completing)
        boolean gatewayInvoked = latch.await(5, TimeUnit.SECONDS);
        assertThat(gatewayInvoked).as("AI gateway called within 5 s after commit").isTrue();

        // Poll until the persistence step after the gateway call completes
        List<com.example.bankingrisk.risk.model.RiskAlert> alerts = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            alerts = riskAlertRepository.findAll();
            if (!alerts.isEmpty() && alerts.get(0).getMaskedNarrative() != null) break;
            Thread.sleep(100);
        }

        assertThat(capturedPrompt.get())
            .as("prompt must not expose raw UUIDs")
            .doesNotContain(userId.toString())
            .doesNotContain(sourceAccountId.toString())
            .doesNotContain(destAccountId.toString());

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMaskedNarrative()).isNotNull().contains("Risk Summary");
        assertThat(alerts.get(0).getStatus()).isEqualTo(RiskAlertStatus.AI_COMPLETE);
        assertThat(alerts.get(0).getAiAttemptCount()).isEqualTo(1);
        assertThat(alerts.get(0).getAiCompletedAt()).isNotNull();
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", idempotencyKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

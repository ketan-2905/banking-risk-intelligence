package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.RiskReviewAuditRepository;
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
import com.example.bankingrisk.transaction.model.LedgerEntryType;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DoubleReviewRaceIntegrationTest {

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

    private static final long TRANSFER_AMOUNT = 600_000L;

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
        src.setAccountNumber("RACE-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(10_000_000L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("RACE-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        LoginAudit anomaly = new LoginAudit();
        anomaly.setUserId(customerId);
        anomaly.setAnomalyFlag(true);
        loginAuditRepository.save(anomaly);
    }

    @Test
    void concurrentApprove_exactlyOneSucceeds_otherGets409() throws Exception {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Integer> f1 = executor.submit(() -> sendApprove(alertId, barrier));
        Future<Integer> f2 = executor.submit(() -> sendApprove(alertId, barrier));

        executor.shutdown();

        List<Integer> statuses = List.of(f1.get(), f2.get());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void concurrentApprove_noDoubleLedgerEntry() throws Exception {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> sendApprove(alertId, barrier));
        executor.submit(() -> sendApprove(alertId, barrier));
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        long settlementDebits = ledgerEntryRepository.findAll().stream()
            .filter(e -> e.getEntryType() == LedgerEntryType.HELD_TO_SETTLED_DEBIT)
            .count();
        assertThat(settlementDebits).isEqualTo(1);
    }

    @Test
    void concurrentApprove_exactlyOneAuditRow() throws Exception {
        UUID alertId = createHighRiskTransferAndGetAlertId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> sendApprove(alertId, barrier));
        executor.submit(() -> sendApprove(alertId, barrier));
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(auditRepository.findByAlertId(alertId)).hasSize(1);
    }

    private int sendApprove(UUID alertId, CyclicBarrier barrier) {
        try {
            barrier.await(); // synchronize both threads at the starting line
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Approved by race thread."), analystHeaders()),
            String.class);
        return resp.getStatusCode().value();
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

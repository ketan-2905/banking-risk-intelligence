package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.Beneficiary;
import com.example.bankingrisk.transaction.model.LedgerEntryType;
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
class LowRiskTransferIntegrationTest {

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
        src.setAccountNumber("LOW-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(100_000_00L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("LOW-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // Pre-register beneficiary → no NEW_BENEFICIARY rule → score 20 (age only) → LOW
        Beneficiary b = new Beneficiary();
        b.setOwnerUserId(userId);
        b.setDestinationAccountId(destAccountId);
        beneficiaryRepository.save(b);
    }

    @Test
    void lowRiskTransfer_posts_withTwoLedgerEntries() {
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, 1_000_00L, "USD");
        ResponseEntity<CreateTransferResponse> resp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("POSTED");

        // score=20 (account age) → LOW
        assertThat(resp.getBody().riskLevel()).isEqualTo("LOW");

        // Exactly 2 ledger entries: DEBIT_AVAILABLE + CREDIT_AVAILABLE
        var entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(e -> e.getEntryType().name())
            .containsExactlyInAnyOrder(
                LedgerEntryType.DEBIT_AVAILABLE.name(),
                LedgerEntryType.CREDIT_AVAILABLE.name()
            );
    }

    @Test
    void lowRiskTransfer_updatesBalancesCorrectly() {
        long amount = 5_000_00L;
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, amount, "USD");
        restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, headers(UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        Account src = accountRepository.findById(sourceAccountId).orElseThrow();
        Account dst = accountRepository.findById(destAccountId).orElseThrow();

        assertThat(src.getAvailableBalanceMinor()).isEqualTo(100_000_00L - amount);
        assertThat(src.getHeldBalanceMinor()).isEqualTo(0L);
        assertThat(dst.getAvailableBalanceMinor()).isEqualTo(amount);
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", idempotencyKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

package com.example.bankingrisk.integration;

import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.ApiErrorResponse;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
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

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class TransferApiIntegrationTest {

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
    @Autowired StringRedisTemplate redisTemplate;

    private UUID user1Id;
    private UUID user2Id;
    private UUID account1Id;
    private UUID account2Id;

    @BeforeEach
    void setup() {
        // Flush Redis state so rate-limit and idempotency counters don't bleed between tests
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        user1Id = UUID.randomUUID();
        user2Id = UUID.randomUUID();

        Account a1 = new Account();
        a1.setOwnerUserId(user1Id);
        a1.setAccountNumber("TEST-" + UUID.randomUUID());
        a1.setCurrency("USD");
        a1.setAvailableBalanceMinor(100_000_00L);
        a1.setHeldBalanceMinor(0L);
        account1Id = accountRepository.save(a1).getId();

        Account a2 = new Account();
        a2.setOwnerUserId(user2Id);
        a2.setAccountNumber("TEST-" + UUID.randomUUID());
        a2.setCurrency("USD");
        a2.setAvailableBalanceMinor(50_000_00L);
        a2.setHeldBalanceMinor(0L);
        account2Id = accountRepository.save(a2).getId();
    }

    private HttpHeaders customerHeaders(UUID userId, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(userId, Set.of("CUSTOMER")));
        h.set("Idempotency-Key", idempotencyKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    void validTransfer_createsExactlyOneTransferAndTwoLedgerEntries() {
        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_000_00L, "USD");
        ResponseEntity<CreateTransferResponse> resp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user1Id, UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().transferId()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("POSTED");

        assertThat(transferRepository.findAll()).hasSize(1);
        assertThat(ledgerEntryRepository.findAll()).hasSize(2);
    }

    @Test
    void validTransfer_updatesBalancesCorrectly() {
        long amount = 5_000_00L;
        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, amount, "USD");
        restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user1Id, UUID.randomUUID().toString())),
            CreateTransferResponse.class);

        Account src = accountRepository.findById(account1Id).orElseThrow();
        Account dst = accountRepository.findById(account2Id).orElseThrow();

        assertThat(src.getAvailableBalanceMinor()).isEqualTo(100_000_00L - amount);
        assertThat(dst.getAvailableBalanceMinor()).isEqualTo(50_000_00L + amount);
    }

    @Test
    void retryWithSameIdempotencyKey_returnsSameTransferId_noExtraEntries() {
        String idemKey = UUID.randomUUID().toString();
        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_00L, "USD");

        ResponseEntity<CreateTransferResponse> r1 = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user1Id, idemKey)), CreateTransferResponse.class);
        ResponseEntity<CreateTransferResponse> r2 = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user1Id, idemKey)), CreateTransferResponse.class);

        assertThat(r1.getBody()).isNotNull();
        assertThat(r2.getBody()).isNotNull();
        assertThat(r1.getBody().transferId()).isEqualTo(r2.getBody().transferId());
        assertThat(transferRepository.findAll()).hasSize(1);
        assertThat(ledgerEntryRepository.findAll()).hasSize(2);
    }

    // ── Validation errors ───────────────────────────────────────────────────────

    @Test
    void missingIdempotencyKey_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(user1Id, Set.of("CUSTOMER")));
        h.setContentType(MediaType.APPLICATION_JSON);

        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_00L, "USD");
        ResponseEntity<ApiErrorResponse> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, h), ApiErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void insufficientFunds_returns409() {
        CreateTransferRequest req = new CreateTransferRequest(
            account1Id, account2Id, 999_999_999_00L, "USD"); // way more than available
        ResponseEntity<ApiErrorResponse> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user1Id, UUID.randomUUID().toString())),
            ApiErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void userCannotDebitAccountTheyDoNotOwn_returns403() {
        // user2 tries to debit account1 (owned by user1)
        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_00L, "USD");
        ResponseEntity<ApiErrorResponse> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, customerHeaders(user2Id, UUID.randomUUID().toString())),
            ApiErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("FORBIDDEN");
    }

    // ── Security tests ──────────────────────────────────────────────────────────

    @Test
    void noAuth_returns401() {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);

        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_00L, "USD");
        ResponseEntity<String> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void analystRole_cannotCreateTransfer_returns403() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwtTokenService.generateToken(user1Id, Set.of("ANALYST")));
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);

        CreateTransferRequest req = new CreateTransferRequest(account1Id, account2Id, 1_00L, "USD");
        ResponseEntity<String> resp = restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(req, h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

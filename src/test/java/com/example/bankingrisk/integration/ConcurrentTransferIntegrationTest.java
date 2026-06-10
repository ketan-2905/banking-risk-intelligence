package com.example.bankingrisk.integration;

import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.TransferRepository;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConcurrentTransferIntegrationTest {

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

    private UUID userId;
    private UUID sourceAccountId;
    private UUID destAccountId;

    @BeforeEach
    void setup() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        userId = UUID.randomUUID();

        Account src = new Account();
        src.setOwnerUserId(userId);
        src.setAccountNumber("CONC-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(500_000_00L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("CONC-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKey_onlyOneTransferCreated()
        throws InterruptedException {

        String idemKey = UUID.randomUUID().toString();
        String token = jwtTokenService.generateToken(userId, Set.of("CUSTOMER"));
        CreateTransferRequest req = new CreateTransferRequest(sourceAccountId, destAccountId, 1_000_00L, "USD");

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<CreateTransferResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                HttpHeaders h = new HttpHeaders();
                h.set("Authorization", "Bearer " + token);
                h.set("Idempotency-Key", idemKey);
                h.setContentType(MediaType.APPLICATION_JSON);
                return restTemplate.exchange("/api/transfers", HttpMethod.POST,
                    new HttpEntity<>(req, h), CreateTransferResponse.class);
            }));
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Collect all 2xx responses
        List<CreateTransferResponse> successes = futures.stream()
            .map(f -> {
                try {
                    ResponseEntity<CreateTransferResponse> r = f.get();
                    return r.getStatusCode().is2xxSuccessful() ? r.getBody() : null;
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();

        // All successful responses must reference the same transfer
        Set<UUID> ids = successes.stream()
            .map(CreateTransferResponse::transferId)
            .collect(Collectors.toSet());
        assertThat(ids).hasSize(1);

        // Exactly one transfer row and exactly one debit+credit pair in DB
        assertThat(transferRepository.findAll()).hasSize(1);
        assertThat(ledgerEntryRepository.findAll()).hasSize(2);

        // Balance debited exactly once
        Account src = accountRepository.findById(sourceAccountId).orElseThrow();
        assertThat(src.getAvailableBalanceMinor()).isEqualTo(500_000_00L - 1_000_00L);
    }
}

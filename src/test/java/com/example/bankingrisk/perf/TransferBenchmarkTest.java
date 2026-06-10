package com.example.bankingrisk.perf;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.security.JwtTokenService;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark harness for core ledger API latency.
 *
 * Measures ONLY the HTTP round-trip to the transfer endpoint (core ledger path).
 * Async AI completion is intentionally excluded — it runs off-thread after the
 * HTTP response is returned and must be measured separately via actuator metrics.
 *
 * To run this benchmark alone (slow, skipped in CI by default):
 *   mvn test -pl . -Dgroups=benchmark -Dtest=TransferBenchmarkTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Tag("benchmark")
class TransferBenchmarkTest {

    private static final int WARMUP_REQUESTS   = 20;
    private static final int MEASURED_REQUESTS = 100;
    private static final int CONCURRENCY       = 4;

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banking_risk_bench")
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

    // One large source account per test; unique destination per request to keep risk LOW
    private UUID ownerId;
    private UUID sourceAccountId;

    @BeforeEach
    void setup() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();
        ledgerEntryRepository.deleteAll();
        riskAlertRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        ownerId = UUID.randomUUID();

        Account src = new Account();
        src.setOwnerUserId(ownerId);
        src.setAccountNumber("BENCH-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(Long.MAX_VALUE / 2);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();
    }

    /**
     * LOW-risk transfer benchmark: measures core ledger latency (no AI path involved).
     * Reports p50 / p95 / p99 latency in ms and throughput in req/s.
     * Assertions verify functional correctness; latency results are printed only —
     * no hard SLA thresholds are asserted because they vary by hardware.
     */
    @Test
    void lowRiskTransfer_coreLatencyBenchmark() throws Exception {
        String ownerToken = jwtTokenService.generateToken(ownerId, Set.of("CUSTOMER"));

        // Each request goes to a fresh destination account (small amount → LOW risk)
        List<UUID> destinations = createDestinationAccounts(WARMUP_REQUESTS + MEASURED_REQUESTS);

        // Warm up — let JIT stabilise before measuring
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            postTransfer(ownerToken, destinations.get(i), 1_00L);
        }
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
            .getConnection().serverCommands().flushDb();

        // Measured phase — sequential to isolate per-request latency
        long[] latenciesMs = new long[MEASURED_REQUESTS];
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < MEASURED_REQUESTS; i++) {
            long start = System.nanoTime();
            ResponseEntity<CreateTransferResponse> resp =
                postTransfer(ownerToken, destinations.get(WARMUP_REQUESTS + i), 1_00L);
            latenciesMs[i] = (System.nanoTime() - start) / 1_000_000;
            if (resp.getStatusCode().is2xxSuccessful()) successCount.incrementAndGet();
        }

        long totalMs = LongStream.of(latenciesMs).sum();
        double throughput = (successCount.get() * 1000.0) / totalMs;

        Arrays.sort(latenciesMs);
        long p50 = percentile(latenciesMs, 50);
        long p95 = percentile(latenciesMs, 95);
        long p99 = percentile(latenciesMs, 99);

        System.out.printf(
            "%n=== LOW-RISK TRANSFER BENCHMARK (n=%d, concurrency=1) ===%n" +
            "  Successful:  %d/%d%n" +
            "  p50 latency: %d ms%n" +
            "  p95 latency: %d ms%n" +
            "  p99 latency: %d ms%n" +
            "  Throughput:  %.1f req/s (sequential — excludes async AI)%n",
            MEASURED_REQUESTS, successCount.get(), MEASURED_REQUESTS,
            p50, p95, p99, throughput);

        assertThat(successCount.get()).isEqualTo(MEASURED_REQUESTS);
    }

    /**
     * Concurrency benchmark: fires MEASURED_REQUESTS with CONCURRENCY parallel threads.
     * Each request has a unique idempotency key, verifying no duplicate transfers are created.
     */
    @Test
    void concurrentLowRiskTransfers_noDuplicates() throws Exception {
        String ownerToken = jwtTokenService.generateToken(ownerId, Set.of("CUSTOMER"));
        List<UUID> destinations = createDestinationAccounts(MEASURED_REQUESTS);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<ResponseEntity<CreateTransferResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < MEASURED_REQUESTS; i++) {
            final UUID dest = destinations.get(i);
            futures.add(pool.submit(() -> postTransfer(ownerToken, dest, 1_00L)));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long successCount = futures.stream()
            .map(f -> { try { return f.get(); } catch (Exception e) { return null; } })
            .filter(r -> r != null && r.getStatusCode().is2xxSuccessful())
            .count();

        // All requests have unique idempotency keys → all should succeed
        assertThat(successCount).isEqualTo(MEASURED_REQUESTS);

        // No duplicate transfers (DB-level uniqueness enforced)
        long dbTransferCount = transferRepository.count();
        assertThat(dbTransferCount).isEqualTo(MEASURED_REQUESTS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<CreateTransferResponse> postTransfer(
            String token, UUID destinationAccountId, long amountMinor) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/transfers", HttpMethod.POST,
            new HttpEntity<>(new CreateTransferRequest(sourceAccountId, destinationAccountId, amountMinor, "USD"), h),
            CreateTransferResponse.class);
    }

    private List<UUID> createDestinationAccounts(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Account a = new Account();
            a.setOwnerUserId(UUID.randomUUID());
            a.setAccountNumber("BENCH-DST-" + UUID.randomUUID());
            a.setCurrency("USD");
            a.setAvailableBalanceMinor(0L);
            a.setHeldBalanceMinor(0L);
            ids.add(accountRepository.save(a).getId());
        }
        return ids;
    }

    private static long percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}

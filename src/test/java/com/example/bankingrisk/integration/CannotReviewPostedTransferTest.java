package com.example.bankingrisk.integration;

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
import com.example.bankingrisk.transaction.model.Beneficiary;
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
class CannotReviewPostedTransferTest {

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

    // AI gateway mock not strictly needed (LOW risk fires no AI event) but added for safety.
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
        src.setAccountNumber("POSTED-SRC-" + UUID.randomUUID());
        src.setCurrency("USD");
        src.setAvailableBalanceMinor(10_000_000L);
        src.setHeldBalanceMinor(0L);
        sourceAccountId = accountRepository.save(src).getId();

        Account dst = new Account();
        dst.setOwnerUserId(UUID.randomUUID());
        dst.setAccountNumber("POSTED-DST-" + UUID.randomUUID());
        dst.setCurrency("USD");
        dst.setAvailableBalanceMinor(0L);
        dst.setHeldBalanceMinor(0L);
        destAccountId = accountRepository.save(dst).getId();

        // Register beneficiary to suppress NEW_BENEFICIARY rule (+25).
        // With only ACCOUNT_AGE_UNDER_7_DAYS (+20), score = 20 → LOW → POSTED transfer.
        Beneficiary ben = new Beneficiary();
        ben.setOwnerUserId(customerId);
        ben.setDestinationAccountId(destAccountId);
        beneficiaryRepository.save(ben);
    }

    @Test
    void approvePostedAlert_returns409() {
        // Low-risk transfer: 20 pts (account age only) → POSTED, alert = LOW_NO_AI
        ResponseEntity<CreateTransferResponse> transferResp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(
                new CreateTransferRequest(sourceAccountId, destAccountId, 1_000L, "USD"),
                customerTransferHeaders()),
            CreateTransferResponse.class);

        assertThat(transferResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transferResp.getBody()).isNotNull();
        assertThat(transferResp.getBody().status()).isEqualTo(TransferStatus.POSTED.name());
        assertThat(transferResp.getBody().riskLevel()).isEqualTo("LOW");

        var alerts = riskAlertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getStatus().name()).isEqualTo("LOW_NO_AI");

        UUID alertId = alerts.get(0).getId();

        ResponseEntity<String> approveResp = restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Attempt to approve a posted transfer."), analystHeaders()),
            String.class);

        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectPostedAlert_returns409() {
        ResponseEntity<CreateTransferResponse> transferResp = restTemplate.exchange(
            "/api/transfers", HttpMethod.POST,
            new HttpEntity<>(
                new CreateTransferRequest(sourceAccountId, destAccountId, 1_000L, "USD"),
                customerTransferHeaders()),
            CreateTransferResponse.class);

        assertThat(transferResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID alertId = riskAlertRepository.findAll().get(0).getId();

        ResponseEntity<String> rejectResp = restTemplate.exchange(
            "/api/analyst/alerts/" + alertId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Attempt to reject a posted transfer."), analystHeaders()),
            String.class);

        assertThat(rejectResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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

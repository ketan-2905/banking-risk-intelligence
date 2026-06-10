package com.example.bankingrisk.integration;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.Transfer;
import com.example.bankingrisk.transaction.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banking_risk_test")
            .withUsername("banking")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway must also use same datasource
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired AccountRepository accountRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired RiskAlertRepository riskAlertRepository;

    @Test
    void flyway_migrations_create_schema() {
        // If we got here the schema exists; just assert table is queryable
        assertThat(accountRepository.findAll()).isNotNull();
    }

    @Test
    void account_save_and_fetch() {
        Account account = new Account();
        account.setOwnerUserId(UUID.randomUUID());
        account.setAccountNumber("TEST-ACC-" + UUID.randomUUID());
        account.setCurrency("USD");
        account.setAvailableBalanceMinor(100_000_00L);
        account.setHeldBalanceMinor(0L);

        Account saved = accountRepository.save(account);
        Account fetched = accountRepository.findById(saved.getId()).orElseThrow();

        assertThat(fetched.getAvailableBalanceMinor()).isEqualTo(100_000_00L);
        assertThat(fetched.getCurrency()).isEqualTo("USD");
        assertThat(fetched.getCreatedAt()).isNotNull();
    }

    @Test
    void transfer_requestId_unique_constraint_enforced() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String requestId = "IDEM-" + UUID.randomUUID();

        Transfer t1 = makeTransfer(requestId, sourceId, destId, ownerId);
        transferRepository.save(t1);
        transferRepository.flush();

        Transfer t2 = makeTransfer(requestId, sourceId, destId, ownerId);
        assertThatThrownBy(() -> {
            transferRepository.save(t2);
            transferRepository.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void riskAlert_save_and_queryByStatus() {
        UUID transferId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        RiskAlert alert = new RiskAlert();
        alert.setTransferId(transferId);
        alert.setOwnerUserId(ownerId);
        alert.setRiskScore(85);
        alert.setRiskLevel(RiskLevel.HIGH);
        alert.setStatus(RiskAlertStatus.REVIEW_PENDING);
        alert.setPriority(true);
        riskAlertRepository.save(alert);

        List<RiskAlert> found = riskAlertRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(RiskAlertStatus.REVIEW_PENDING));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRiskScore()).isEqualTo(85);
        assertThat(found.get(0).isPriority()).isTrue();
    }

    @Test
    void account_findByIdAndOwnerUserId_returnsEmpty_forWrongOwner() {
        Account account = new Account();
        UUID realOwner = UUID.randomUUID();
        account.setOwnerUserId(realOwner);
        account.setAccountNumber("TEST-AUTH-" + UUID.randomUUID());
        account.setCurrency("USD");
        account.setAvailableBalanceMinor(50_00L);
        account.setHeldBalanceMinor(0L);
        Account saved = accountRepository.save(account);

        assertThat(accountRepository.findByIdAndOwnerUserId(saved.getId(), UUID.randomUUID()))
            .isEmpty();
        assertThat(accountRepository.findByIdAndOwnerUserId(saved.getId(), realOwner))
            .isPresent();
    }

    private Transfer makeTransfer(String requestId, UUID sourceId, UUID destId, UUID ownerId) {
        Transfer t = new Transfer();
        t.setRequestId(requestId);
        t.setSourceAccountId(sourceId);
        t.setDestinationAccountId(destId);
        t.setOwnerUserId(ownerId);
        t.setAmountMinor(1_000_00L);
        t.setCurrency("USD");
        t.setStatus(TransferStatus.INITIATED);
        t.setRiskScore(0);
        t.setRiskLevel(RiskLevel.LOW);
        return t;
    }
}

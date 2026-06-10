package com.example.bankingrisk.unit;

import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.context.RiskContextBuilder;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskContextBuilderTest {

    @Mock TransferRepository transferRepository;
    @Mock BeneficiaryRepository beneficiaryRepository;
    @Mock LoginAuditRepository loginAuditRepository;

    @InjectMocks RiskContextBuilder builder;

    @Test
    void calculatesAccountAge() {
        Account source = makeAccount(Instant.now().minus(10, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());

        stubDefaults(source, dest, 0L, true, false);

        RiskContext ctx = builder.build(UUID.randomUUID(), UUID.randomUUID(), source, dest, 100_00L, "USD");

        assertThat(ctx.accountAgeDays()).isEqualTo(10);
    }

    @Test
    void includesCurrentAmountInVelocity() {
        Account source = makeAccount(Instant.now().minus(10, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());
        long historical = 200_000L;
        long requested = 100_000L;

        when(transferRepository.sumRecentOutgoingAmounts(eq(source.getId()), any(), any()))
            .thenReturn(historical);
        when(beneficiaryRepository.existsByOwnerUserIdAndDestinationAccountId(any(), any())).thenReturn(true);
        when(loginAuditRepository.existsByUserIdAndAnomalyFlagTrueAndCreatedAtAfter(any(), any())).thenReturn(false);

        RiskContext ctx = builder.build(UUID.randomUUID(), UUID.randomUUID(), source, dest, requested, "USD");

        assertThat(ctx.hourlyTransferVelocityMinor()).isEqualTo(historical + requested);
    }

    @Test
    void detectsUnseenBeneficiary() {
        Account source = makeAccount(Instant.now().minus(10, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());

        stubDefaults(source, dest, 0L, false, false);

        RiskContext ctx = builder.build(UUID.randomUUID(), UUID.randomUUID(), source, dest, 100_00L, "USD");

        assertThat(ctx.beneficiarySeenBefore()).isFalse();
    }

    @Test
    void detectsSeenBeneficiary() {
        Account source = makeAccount(Instant.now().minus(10, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());

        stubDefaults(source, dest, 0L, true, false);

        RiskContext ctx = builder.build(UUID.randomUUID(), UUID.randomUUID(), source, dest, 100_00L, "USD");

        assertThat(ctx.beneficiarySeenBefore()).isTrue();
    }

    @Test
    void detectsLoginAnomaly() {
        Account source = makeAccount(Instant.now().minus(10, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());

        stubDefaults(source, dest, 0L, true, true);

        RiskContext ctx = builder.build(UUID.randomUUID(), UUID.randomUUID(), source, dest, 100_00L, "USD");

        assertThat(ctx.loginAnomalyFlag()).isTrue();
    }

    @Test
    void snapshotContainsTransferAndAccountIds() {
        UUID transferId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Account source = makeAccount(Instant.now().minus(5, ChronoUnit.DAYS));
        Account dest = makeAccount(Instant.now());

        stubDefaults(source, dest, 0L, true, false);

        RiskContext ctx = builder.build(transferId, userId, source, dest, 50_00L, "USD");

        assertThat(ctx.transferId()).isEqualTo(transferId);
        assertThat(ctx.ownerUserId()).isEqualTo(userId);
        assertThat(ctx.sourceAccountId()).isEqualTo(source.getId());
        assertThat(ctx.destinationAccountId()).isEqualTo(dest.getId());
        assertThat(ctx.amountMinor()).isEqualTo(50_00L);
        assertThat(ctx.currency()).isEqualTo("USD");
        assertThat(ctx.builtAt()).isNotNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubDefaults(Account source, Account dest,
                               long velocity, boolean beneficiary, boolean loginAnomaly) {
        when(transferRepository.sumRecentOutgoingAmounts(eq(source.getId()),
            eq(List.of(TransferStatus.POSTED, TransferStatus.PENDING_REVIEW)), any()))
            .thenReturn(velocity);
        when(beneficiaryRepository.existsByOwnerUserIdAndDestinationAccountId(any(), eq(dest.getId())))
            .thenReturn(beneficiary);
        when(loginAuditRepository.existsByUserIdAndAnomalyFlagTrueAndCreatedAtAfter(any(), any()))
            .thenReturn(loginAnomaly);
    }

    private Account makeAccount(Instant createdAt) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(UUID.randomUUID());
        account.setAccountNumber("TEST-" + UUID.randomUUID());
        account.setCurrency("USD");
        account.setAvailableBalanceMinor(100_000_00L);
        account.setHeldBalanceMinor(0L);
        ReflectionTestUtils.setField(account, "createdAt", createdAt);
        return account;
    }
}

package com.example.bankingrisk.risk.context;

import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.TransferStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
public class RiskContextBuilder {

    private final TransferRepository transferRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final LoginAuditRepository loginAuditRepository;

    public RiskContextBuilder(
            TransferRepository transferRepository,
            BeneficiaryRepository beneficiaryRepository,
            LoginAuditRepository loginAuditRepository) {
        this.transferRepository = transferRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.loginAuditRepository = loginAuditRepository;
    }

    public RiskContext build(
            UUID transferId,
            UUID ownerUserId,
            Account source,
            Account destination,
            long amountMinor,
            String currency) {

        Instant now = Instant.now();

        long accountAgeDays = ChronoUnit.DAYS.between(source.getCreatedAt(), now);

        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        long historicalVelocity = transferRepository.sumRecentOutgoingAmounts(
            source.getId(),
            List.of(TransferStatus.POSTED, TransferStatus.PENDING_REVIEW),
            oneHourAgo
        );
        long hourlyTransferVelocityMinor = historicalVelocity + amountMinor;

        boolean beneficiarySeenBefore = beneficiaryRepository
            .existsByOwnerUserIdAndDestinationAccountId(ownerUserId, destination.getId());

        Instant twentyFourHoursAgo = now.minus(24, ChronoUnit.HOURS);
        boolean loginAnomalyFlag = loginAuditRepository
            .existsByUserIdAndAnomalyFlagTrueAndCreatedAtAfter(ownerUserId, twentyFourHoursAgo);

        return new RiskContext(
            transferId,
            ownerUserId,
            source.getId(),
            destination.getId(),
            amountMinor,
            currency,
            accountAgeDays,
            hourlyTransferVelocityMinor,
            beneficiarySeenBefore,
            loginAnomalyFlag,
            now
        );
    }
}

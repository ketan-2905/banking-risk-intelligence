package com.example.bankingrisk.analyst;

import com.example.bankingrisk.analyst.dto.AlertDetailResponse;
import com.example.bankingrisk.analyst.dto.AlertDetailResponse.LedgerEntrySummary;
import com.example.bankingrisk.analyst.dto.AlertSummaryResponse;
import com.example.bankingrisk.analyst.dto.ReviewDecisionResponse;
import com.example.bankingrisk.exception.AlertAlreadyReviewedException;
import com.example.bankingrisk.exception.AlertNotFoundException;
import com.example.bankingrisk.observability.MetricsService;
import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.RiskReviewAuditRepository;
import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import com.example.bankingrisk.risk.model.RiskReviewAudit;
import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.LedgerEntryRepository;
import com.example.bankingrisk.transaction.TransferRepository;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.LedgerEntry;
import com.example.bankingrisk.transaction.model.LedgerEntryType;
import com.example.bankingrisk.transaction.model.Transfer;
import com.example.bankingrisk.transaction.model.TransferStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AnalystService {

    private static final TypeReference<Object> JSON_OBJECT = new TypeReference<>() {};

    private final RiskAlertRepository riskAlertRepository;
    private final RiskReviewAuditRepository auditRepository;
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public AnalystService(
            RiskAlertRepository riskAlertRepository,
            RiskReviewAuditRepository auditRepository,
            TransferRepository transferRepository,
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            ObjectMapper objectMapper,
            MetricsService metricsService) {
        this.riskAlertRepository = riskAlertRepository;
        this.auditRepository = auditRepository;
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Transactional(readOnly = true)
    public List<AlertSummaryResponse> listAlerts(RiskAlertStatus status, RiskLevel riskLevel, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<RiskAlert> alerts = riskAlertRepository.findWithFilters(
            status, riskLevel, PageRequest.of(0, safeLimit));
        return alerts.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public AlertDetailResponse getAlert(UUID alertId) {
        RiskAlert alert = riskAlertRepository.findById(alertId)
            .orElseThrow(() -> new AlertNotFoundException(alertId));

        Transfer transfer = transferRepository.findById(alert.getTransferId())
            .orElseThrow(() -> new IllegalStateException("Transfer not found for alert " + alertId));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transfer.getId());
        List<LedgerEntrySummary> ledgerSummary = entries.stream()
            .map(e -> new LedgerEntrySummary(e.getEntryType().name(), e.getAmountMinor(), e.getCreatedAt()))
            .toList();

        return new AlertDetailResponse(
            alert.getId(),
            alert.getTransferId(),
            alert.getRiskScore(),
            alert.getRiskLevel().name(),
            alert.getStatus().name(),
            alert.isPriority(),
            parseJson(alert.getTriggeredRulesJson()),
            alert.getMaskedNarrative(),
            alert.getAiErrorCode(),
            transfer.getStatus().name(),
            transfer.getAmountMinor(),
            transfer.getCurrency(),
            ledgerSummary,
            alert.getCreatedAt()
        );
    }

    @Transactional
    public ReviewDecisionResponse approveAlert(UUID alertId, UUID analystUserId, String reason) {
        // Lock alert first to prevent concurrent decisions
        RiskAlert alert = riskAlertRepository.lockById(alertId)
            .orElseThrow(() -> new AlertNotFoundException(alertId));

        if (alert.getStatus() != RiskAlertStatus.REVIEW_PENDING) {
            throw new AlertAlreadyReviewedException(
                "Alert " + alertId + " cannot be approved — current status: " + alert.getStatus());
        }

        // Lock transfer, then accounts in a fixed order to avoid deadlocks
        Transfer transfer = transferRepository.lockById(alert.getTransferId())
            .orElseThrow(() -> new IllegalStateException("Transfer not found for alert " + alertId));

        if (transfer.getStatus() != TransferStatus.PENDING_REVIEW) {
            throw new AlertAlreadyReviewedException(
                "Transfer " + transfer.getId() + " is not pending review: " + transfer.getStatus());
        }

        Account source = accountRepository.lockById(transfer.getSourceAccountId())
            .orElseThrow(() -> new IllegalStateException("Source account not found"));
        Account destination = accountRepository.lockById(transfer.getDestinationAccountId())
            .orElseThrow(() -> new IllegalStateException("Destination account not found"));

        // Settle the held funds (held → permanently debited, not returned to available)
        source.settleHeld(transfer.getAmountMinor());
        accountRepository.save(source);

        // Credit destination
        destination.creditAvailable(transfer.getAmountMinor());
        accountRepository.save(destination);

        // Ledger entries
        saveLedgerEntry(transfer, transfer.getSourceAccountId(),
            LedgerEntryType.HELD_TO_SETTLED_DEBIT,
            "Approved: held funds settled — analyst " + analystUserId);
        saveLedgerEntry(transfer, transfer.getDestinationAccountId(),
            LedgerEntryType.CREDIT_AVAILABLE,
            "Approved: credit from transfer " + transfer.getId());

        // Update statuses
        transfer.setStatus(TransferStatus.APPROVED_SETTLED);
        transferRepository.save(transfer);

        alert.setStatus(RiskAlertStatus.APPROVED);
        riskAlertRepository.save(alert);

        // Audit
        saveAudit(alertId, transfer.getId(), analystUserId, "APPROVED", reason);
        metricsService.incrementAlertDecision("APPROVED");

        return new ReviewDecisionResponse(alertId, "APPROVED",
            RiskAlertStatus.APPROVED.name(), TransferStatus.APPROVED_SETTLED.name());
    }

    @Transactional
    public ReviewDecisionResponse rejectAlert(UUID alertId, UUID analystUserId, String reason) {
        // Lock alert first to prevent concurrent decisions
        RiskAlert alert = riskAlertRepository.lockById(alertId)
            .orElseThrow(() -> new AlertNotFoundException(alertId));

        if (alert.getStatus() != RiskAlertStatus.REVIEW_PENDING) {
            throw new AlertAlreadyReviewedException(
                "Alert " + alertId + " cannot be rejected — current status: " + alert.getStatus());
        }

        // Lock transfer and source account
        Transfer transfer = transferRepository.lockById(alert.getTransferId())
            .orElseThrow(() -> new IllegalStateException("Transfer not found for alert " + alertId));

        if (transfer.getStatus() != TransferStatus.PENDING_REVIEW) {
            throw new AlertAlreadyReviewedException(
                "Transfer " + transfer.getId() + " is not pending review: " + transfer.getStatus());
        }

        Account source = accountRepository.lockById(transfer.getSourceAccountId())
            .orElseThrow(() -> new IllegalStateException("Source account not found"));

        // Release held funds back to available
        source.releaseHold(transfer.getAmountMinor());
        accountRepository.save(source);

        // Ledger entry
        saveLedgerEntry(transfer, transfer.getSourceAccountId(),
            LedgerEntryType.HOLD_RELEASE,
            "Rejected: hold released — analyst " + analystUserId);

        // Update statuses
        transfer.setStatus(TransferStatus.REJECTED_RELEASED);
        transferRepository.save(transfer);

        alert.setStatus(RiskAlertStatus.REJECTED);
        riskAlertRepository.save(alert);

        // Audit
        saveAudit(alertId, transfer.getId(), analystUserId, "REJECTED", reason);
        metricsService.incrementAlertDecision("REJECTED");

        return new ReviewDecisionResponse(alertId, "REJECTED",
            RiskAlertStatus.REJECTED.name(), TransferStatus.REJECTED_RELEASED.name());
    }

    private void saveLedgerEntry(Transfer transfer, UUID accountId,
                                  LedgerEntryType type, String description) {
        LedgerEntry entry = new LedgerEntry();
        entry.setTransferId(transfer.getId());
        entry.setAccountId(accountId);
        entry.setEntryType(type);
        entry.setAmountMinor(transfer.getAmountMinor());
        entry.setCurrency(transfer.getCurrency());
        entry.setDescription(description);
        ledgerEntryRepository.save(entry);
    }

    private void saveAudit(UUID alertId, UUID transferId,
                            UUID analystUserId, String decision, String reason) {
        RiskReviewAudit audit = new RiskReviewAudit();
        audit.setAlertId(alertId);
        audit.setTransferId(transferId);
        audit.setAnalystUserId(analystUserId);
        audit.setDecision(decision);
        audit.setReason(reason);
        auditRepository.save(audit);
    }

    private AlertSummaryResponse toSummary(RiskAlert alert) {
        return new AlertSummaryResponse(
            alert.getId(),
            alert.getTransferId(),
            alert.getRiskScore(),
            alert.getRiskLevel().name(),
            alert.getStatus().name(),
            alert.isPriority(),
            parseJson(alert.getTriggeredRulesJson()),
            alert.getMaskedNarrative(),
            alert.getCreatedAt()
        );
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return json; // fallback: return raw string
        }
    }
}

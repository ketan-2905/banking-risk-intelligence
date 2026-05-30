package com.example.bankingrisk.risk.ai;

import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RiskAlertAiPersistenceService {

    private final RiskAlertRepository riskAlertRepository;

    public RiskAlertAiPersistenceService(RiskAlertRepository riskAlertRepository) {
        this.riskAlertRepository = riskAlertRepository;
    }

    @Transactional
    public void saveNarrative(UUID transferId, RiskLevel riskLevel, String narrative) {
        List<RiskAlert> alerts = riskAlertRepository.findByTransferId(transferId);
        if (alerts.isEmpty()) return;

        RiskAlert alert = alerts.get(0);
        alert.setMaskedNarrative(narrative);
        alert.setAiCompletedAt(Instant.now());
        alert.setAiAttemptCount(alert.getAiAttemptCount() + 1);

        // HIGH stays REVIEW_PENDING — narrative added but human review still required
        if (riskLevel != RiskLevel.HIGH) {
            alert.setStatus(RiskAlertStatus.AI_COMPLETE);
        }

        riskAlertRepository.save(alert);
    }

    @Transactional
    public void saveError(UUID transferId, String errorCode) {
        List<RiskAlert> alerts = riskAlertRepository.findByTransferId(transferId);
        if (alerts.isEmpty()) return;

        RiskAlert alert = alerts.get(0);
        alert.setAiErrorCode(errorCode);
        alert.setAiAttemptCount(alert.getAiAttemptCount() + 1);
        alert.setAiCompletedAt(Instant.now());

        // HIGH stays REVIEW_PENDING — still needs human review regardless of AI outcome
        if (alert.getRiskLevel() != RiskLevel.HIGH) {
            alert.setStatus(RiskAlertStatus.ERROR);
        }

        riskAlertRepository.save(alert);
    }
}

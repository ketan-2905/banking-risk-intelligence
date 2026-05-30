package com.example.bankingrisk.risk.ai;

import com.example.bankingrisk.observability.MetricsService;
import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.engine.RiskEvaluation;
import com.example.bankingrisk.risk.event.TransferRiskEvaluatedEvent;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
public class RiskAlertAiHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertAiHandler.class);

    private final SpringAiGateway gateway;
    private final PiiMaskingService piiMaskingService;
    private final PromptBuilder promptBuilder;
    private final RiskAlertAiPersistenceService persistenceService;
    private final MetricsService metricsService;

    public RiskAlertAiHandler(
            SpringAiGateway gateway,
            PiiMaskingService piiMaskingService,
            PromptBuilder promptBuilder,
            RiskAlertAiPersistenceService persistenceService,
            MetricsService metricsService) {
        this.gateway = gateway;
        this.piiMaskingService = piiMaskingService;
        this.promptBuilder = promptBuilder;
        this.persistenceService = persistenceService;
        this.metricsService = metricsService;
    }

    @Async("riskAiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleRiskEvaluated(TransferRiskEvaluatedEvent event) {
        RiskContext context = event.context();
        RiskEvaluation evaluation = event.evaluation();

        Timer.Sample sample = metricsService.startTimer();
        try {
            MaskedPrompt maskedContext = piiMaskingService.maskContext(context);
            String fullPrompt = promptBuilder.buildPrompt(maskedContext, evaluation);
            String narrative = gateway.generateRiskNarrative(fullPrompt);

            if (narrative != null && !narrative.isBlank()) {
                persistenceService.saveNarrative(context.transferId(), evaluation.riskLevel(), narrative);
                metricsService.recordAiNarrative(sample, "success");
                log.info("event=ai_narrative_saved transfer_id={} risk_level={}",
                    context.transferId(), evaluation.riskLevel());
            } else {
                metricsService.recordAiNarrative(sample, "empty");
                log.info("event=ai_narrative_empty transfer_id={} risk_level={} — leaving status unchanged",
                    context.transferId(), evaluation.riskLevel());
            }
        } catch (Exception ex) {
            metricsService.recordAiNarrative(sample, "error");
            log.warn("event=ai_narrative_error transfer_id={} risk_level={} error={}",
                context.transferId(), evaluation.riskLevel(), ex.getMessage());
            persistenceService.saveError(context.transferId(), truncateErrorCode(ex.getMessage()));
        }
    }

    private static String truncateErrorCode(String message) {
        if (message == null) return "UNKNOWN_AI_ERROR";
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}

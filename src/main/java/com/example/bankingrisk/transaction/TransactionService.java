package com.example.bankingrisk.transaction;

import com.example.bankingrisk.exception.ForbiddenAccountException;
import com.example.bankingrisk.exception.InsufficientFundsException;
import com.example.bankingrisk.exception.InvalidTransferRequestException;
import com.example.bankingrisk.exception.MissingIdempotencyKeyException;
import com.example.bankingrisk.observability.MetricsService;
import com.example.bankingrisk.risk.RiskAlertRepository;
import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.context.RiskContextBuilder;
import com.example.bankingrisk.risk.engine.RiskEvaluation;
import com.example.bankingrisk.risk.engine.RiskRuleEngine;
import com.example.bankingrisk.risk.event.TransferRiskEvaluatedEvent;
import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import com.example.bankingrisk.security.UserPrincipal;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import com.example.bankingrisk.transaction.idempotency.IdempotencyService;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.LedgerEntry;
import com.example.bankingrisk.transaction.model.LedgerEntryType;
import com.example.bankingrisk.transaction.model.Transfer;
import com.example.bankingrisk.transaction.model.TransferStatus;
import com.example.bankingrisk.transaction.ratelimit.RateLimiterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final Set<String> ALLOWED_CURRENCIES = Set.of("USD", "EUR", "GBP");
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RiskAlertRepository riskAlertRepository;
    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final RiskContextBuilder riskContextBuilder;
    private final RiskRuleEngine riskRuleEngine;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final MetricsService metricsService;

    public TransactionService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            RiskAlertRepository riskAlertRepository,
            IdempotencyService idempotencyService,
            RateLimiterService rateLimiterService,
            RiskContextBuilder riskContextBuilder,
            RiskRuleEngine riskRuleEngine,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MetricsService metricsService) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.riskAlertRepository = riskAlertRepository;
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.riskContextBuilder = riskContextBuilder;
        this.riskRuleEngine = riskRuleEngine;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.metricsService = metricsService;
    }

    public CreateTransferResponse createTransfer(
            UserPrincipal principal,
            String idempotencyKey,
            CreateTransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new MissingIdempotencyKeyException(
                "Idempotency-Key must not exceed " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        if (request.amountMinor() <= 0) {
            throw new InvalidTransferRequestException("Amount must be positive");
        }
        if (request.currency() == null || !ALLOWED_CURRENCIES.contains(request.currency())) {
            throw new InvalidTransferRequestException("Unsupported currency: " + request.currency());
        }

        rateLimiterService.checkRateLimit(principal.getUserId());

        String requestId = principal.getUserId() + ":" + idempotencyKey;

        Timer.Sample transferSample = metricsService.startTimer();
        CreateTransferResponse response = idempotencyService.execute(
            principal.getUserId(),
            idempotencyKey,
            CreateTransferResponse.class,
            () -> txTemplate.execute(status -> doTransfer(principal, requestId, request))
        );
        metricsService.recordTransferCreation(transferSample, response.riskLevel());
        return response;
    }

    private CreateTransferResponse doTransfer(
            UserPrincipal principal,
            String requestId,
            CreateTransferRequest request) {

        // DB-level idempotency guardrail — handles Redis-failure race scenarios
        Optional<Transfer> existing = transferRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return buildResponse(existing.get(), "Transfer already recorded (idempotent)");
        }

        // 1. Pessimistic write lock on source account
        Account source = accountRepository.lockById(request.sourceAccountId())
            .orElseThrow(() -> new ForbiddenAccountException("Source account not found"));

        // 2. Validate ownership and currency
        if (!source.getOwnerUserId().equals(principal.getUserId())) {
            throw new ForbiddenAccountException("You do not own source account: " + request.sourceAccountId());
        }
        if (!source.getCurrency().equals(request.currency())) {
            throw new InvalidTransferRequestException(
                "Account currency '%s' does not match request currency '%s'"
                    .formatted(source.getCurrency(), request.currency()));
        }

        // 3. Load destination and validate funds
        Account destination = accountRepository.findById(request.destinationAccountId())
            .orElseThrow(() -> new InvalidTransferRequestException(
                "Destination account not found: " + request.destinationAccountId()));

        if (source.getAvailableBalanceMinor() < request.amountMinor()) {
            throw new InsufficientFundsException(
                "Insufficient funds: available=%d, requested=%d"
                    .formatted(source.getAvailableBalanceMinor(), request.amountMinor()));
        }

        // 4. Create transfer as INITIATED to obtain a persistent ID for risk context
        Transfer transfer = new Transfer();
        transfer.setRequestId(requestId);
        transfer.setSourceAccountId(request.sourceAccountId());
        transfer.setDestinationAccountId(request.destinationAccountId());
        transfer.setOwnerUserId(principal.getUserId());
        transfer.setAmountMinor(request.amountMinor());
        transfer.setCurrency(request.currency());
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setRiskScore(0);
        transfer.setRiskLevel(RiskLevel.LOW);
        transfer = transferRepository.save(transfer);

        // 5. Build risk context (includes velocity from historical transfers + current amount)
        RiskContext context = riskContextBuilder.build(
            transfer.getId(),
            principal.getUserId(),
            source,
            destination,
            request.amountMinor(),
            request.currency()
        );

        // 6. Evaluate deterministic risk rules
        Timer.Sample riskSample = metricsService.startTimer();
        RiskEvaluation evaluation = riskRuleEngine.evaluate(context);
        metricsService.recordRiskEvaluation(riskSample, evaluation.riskLevel().name());

        // 7. Persist score and level onto transfer
        transfer.setRiskScore(evaluation.score());
        transfer.setRiskLevel(evaluation.riskLevel());

        // 8. Save risk alert for every non-zero score (all levels per architecture diagram)
        if (evaluation.score() > 0) {
            RiskAlert alert = new RiskAlert();
            alert.setTransferId(transfer.getId());
            alert.setOwnerUserId(principal.getUserId());
            alert.setRiskScore(evaluation.score());
            alert.setRiskLevel(evaluation.riskLevel());
            alert.setTriggeredRulesJson(toJson(evaluation.triggeredRules()));
            alert.setContextSnapshotJson(toJson(context));
            if (evaluation.riskLevel() == RiskLevel.HIGH) {
                alert.setStatus(RiskAlertStatus.REVIEW_PENDING);
                alert.setPriority(true);
            } else if (evaluation.riskLevel() == RiskLevel.MEDIUM) {
                alert.setStatus(RiskAlertStatus.AI_PENDING);
                alert.setPriority(false);
            } else {
                alert.setStatus(RiskAlertStatus.LOW_NO_AI);
                alert.setPriority(false);
            }
            riskAlertRepository.save(alert);
        }

        // 9. Apply settlement logic based on risk level
        if (evaluation.riskLevel() == RiskLevel.HIGH) {
            // HIGH: move funds from available to held, do NOT credit destination
            source.hold(request.amountMinor());
            accountRepository.save(source);

            transfer.setStatus(TransferStatus.PENDING_REVIEW);

            LedgerEntry holdDebit = new LedgerEntry();
            holdDebit.setTransferId(transfer.getId());
            holdDebit.setAccountId(request.sourceAccountId());
            holdDebit.setEntryType(LedgerEntryType.HOLD_DEBIT);
            holdDebit.setAmountMinor(request.amountMinor());
            holdDebit.setCurrency(request.currency());
            holdDebit.setDescription("HIGH risk hold — pending review for transfer to " + request.destinationAccountId());
            ledgerEntryRepository.save(holdDebit);
        } else {
            // LOW / MEDIUM: normal settlement — debit source, credit destination immediately
            source.debitAvailable(request.amountMinor());
            destination.creditAvailable(request.amountMinor());
            accountRepository.save(source);
            accountRepository.save(destination);

            transfer.setStatus(TransferStatus.POSTED);

            LedgerEntry debit = new LedgerEntry();
            debit.setTransferId(transfer.getId());
            debit.setAccountId(request.sourceAccountId());
            debit.setEntryType(LedgerEntryType.DEBIT_AVAILABLE);
            debit.setAmountMinor(request.amountMinor());
            debit.setCurrency(request.currency());
            debit.setDescription("Transfer debit to " + request.destinationAccountId());
            ledgerEntryRepository.save(debit);

            LedgerEntry credit = new LedgerEntry();
            credit.setTransferId(transfer.getId());
            credit.setAccountId(request.destinationAccountId());
            credit.setEntryType(LedgerEntryType.CREDIT_AVAILABLE);
            credit.setAmountMinor(request.amountMinor());
            credit.setCurrency(request.currency());
            credit.setDescription("Transfer credit from " + request.sourceAccountId());
            ledgerEntryRepository.save(credit);
        }

        // Persist final transfer status
        transfer = transferRepository.save(transfer);

        metricsService.incrementRiskLevelCount(evaluation.riskLevel().name());
        log.info("event=transfer_created transfer_id={} risk_level={} risk_score={} status={}",
            transfer.getId(), evaluation.riskLevel(), evaluation.score(), transfer.getStatus());

        // 10. Publish event for AI triage — delivered only after commit via @TransactionalEventListener
        // MEDIUM: async narrative; HIGH: review queue. LOW needs no AI work.
        if (evaluation.riskLevel() == RiskLevel.MEDIUM || evaluation.riskLevel() == RiskLevel.HIGH) {
            eventPublisher.publishEvent(new TransferRiskEvaluatedEvent(context, evaluation));
        }

        return buildResponse(transfer, settlementMessage(evaluation.riskLevel()));
    }

    private static String settlementMessage(RiskLevel level) {
        return switch (level) {
            case HIGH -> "Transfer held pending review";
            case MEDIUM -> "Transfer posted — flagged for AI review";
            case LOW -> "Transfer posted successfully";
        };
    }

    private CreateTransferResponse buildResponse(Transfer transfer, String message) {
        return new CreateTransferResponse(
            transfer.getId(),
            transfer.getStatus().name(),
            transfer.getRiskScore(),
            transfer.getRiskLevel() != null ? transfer.getRiskLevel().name() : RiskLevel.LOW.name(),
            message
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize risk data to JSON", e);
        }
    }
}

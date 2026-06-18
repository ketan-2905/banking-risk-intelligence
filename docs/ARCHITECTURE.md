# Architecture — Banking Risk Intelligence

## Layer overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        HTTP Layer                               │
│  TransactionController  │  AnalystController  │  DashboardCtrl │
└───────────────┬─────────────────┬─────────────────┬────────────┘
                │                 │                 │
┌───────────────▼─────────────────▼─────────────────▼────────────┐
│                     Service Layer                               │
│  TransactionService  │  AnalystService  │  RiskAlertAiHandler  │
└───────────────┬──────────────────────────────────┬─────────────┘
                │                                  │
┌───────────────▼──────────────┐  ┌────────────────▼─────────────┐
│       Risk Pipeline          │  │       Async AI Pipeline       │
│  RiskContextBuilder          │  │  PiiMaskingService            │
│  RiskRuleEngine              │  │  PromptBuilder                │
│  RiskEvaluation              │  │  OllamaAiGateway (optional)   │
└───────────────┬──────────────┘  └──────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│                     Data Layer                                  │
│  AccountRepository  TransferRepository  LedgerEntryRepository  │
│  RiskAlertRepository  RiskReviewAuditRepository                 │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────┐  ┌─────────────────────┐
│           PostgreSQL                │  │       Redis          │
│  accounts, transfers, ledger,       │  │  Idempotency lock   │
│  risk_alerts, risk_review_audits    │  │  Idempotency cache  │
└─────────────────────────────────────┘  │  Rate limit counter │
                                         └─────────────────────┘
```

## Transaction flow

```
POST /api/transfers
  │
  ├── 1. Validate request (idempotency key, amount, currency)
  ├── 2. Rate-limit check (Redis sliding counter, 20 req/min default)
  ├── 3. Idempotency check (Redis cache → return cached if hit)
  ├── 4. Begin DB transaction (TransactionTemplate)
  │     ├── 4a. DB-level idempotency guard (requestId UNIQUE index)
  │     ├── 4b. PESSIMISTIC_WRITE lock on source account
  │     ├── 4c. Validate ownership + currency match
  │     ├── 4d. Load destination account
  │     ├── 4e. Funds check (available >= amount)
  │     ├── 4f. Save Transfer (status=INITIATED)
  │     ├── 4g. Build RiskContext (velocity, account age, amount ratio)
  │     ├── 4h. RiskRuleEngine.evaluate() → RiskEvaluation
  │     ├── 4i. Save RiskAlert (if score > 0)
  │     ├── 4j. Settlement branch:
  │     │     LOW/MEDIUM → DEBIT_AVAILABLE + CREDIT_AVAILABLE; status=POSTED
  │     │     HIGH       → HOLD_DEBIT only;                    status=PENDING_REVIEW
  │     └── 4k. Publish TransferRiskEvaluatedEvent (MEDIUM/HIGH only)
  ├── 5. Cache response in Redis (24h TTL)
  └── 6. Return 201 CreateTransferResponse
       (AI narrative runs OFF-THREAD after commit — not in this response)
```

## Risk flow

```
RiskContextBuilder:
  - transfer amount vs account 30-day average (velocity ratio)
  - transfer amount vs available balance (balance ratio)
  - new account flag (account age < 30 days)
  - large absolute amount (> $10,000 USD-equivalent)

RiskRuleEngine (deterministic, no ML):
  - Each rule adds to score and records TriggeredRule with evidence text
  - Score 0-24  → LOW   (DEBIT + CREDIT immediately, no AI)
  - Score 25-59 → MEDIUM (DEBIT + CREDIT immediately, async AI narrative)
  - Score 60+   → HIGH  (HOLD only, PENDING_REVIEW, async AI narrative)
```

## Async AI flow

```
TransferRiskEvaluatedEvent published inside DB transaction
  │
  └── AFTER_COMMIT → @Async("riskAiTaskExecutor")
        │
        ├── PiiMaskingService.maskContext()
        │     Replaces: account UUIDs, owner UUIDs → CUSTOMER_TOKEN_N
        │     Redacts: email patterns, phone patterns
        │
        ├── PromptBuilder.buildPrompt(maskedContext, evaluation)
        │     No raw PII in prompt — only masked tokens
        │
        ├── OllamaAiGateway.generateRiskNarrative(prompt)
        │     Disabled by default (ai.ollama.enabled=false)
        │     Timeout: 5s; failure → log + saveError; ledger unaffected
        │
        └── RiskAlertAiPersistenceService.saveNarrative()
              Saves masked narrative back to risk_alerts.masked_narrative
```

## Escrow review flow

```
HIGH-risk transfer (status=PENDING_REVIEW, alert status=REVIEW_PENDING)
  │
  ├── GET /api/analyst/alerts         → list pending alerts (ANALYST/ADMIN only)
  ├── GET /api/analyst/alerts/{id}    → detail with ledger entries
  │
  ├── POST /api/analyst/alerts/{id}/approve
  │     PESSIMISTIC_WRITE lock order: alert → transfer → source → destination
  │     source.settleHeld()       (heldBalance → 0, availableBalance unchanged)
  │     destination.creditAvailable()
  │     Ledger: HELD_TO_SETTLED_DEBIT + CREDIT_AVAILABLE
  │     Transfer → APPROVED_SETTLED; Alert → APPROVED
  │     RiskReviewAudit row written (immutable)
  │
  └── POST /api/analyst/alerts/{id}/reject
        PESSIMISTIC_WRITE lock order: alert → transfer → source
        source.releaseHold()      (heldBalance → 0, availableBalance restored)
        Ledger: HOLD_RELEASE
        Transfer → REJECTED_RELEASED; Alert → REJECTED
        RiskReviewAudit row written (immutable)

Double-review protection: status check inside lock → 409 CONFLICT if already reviewed
```

## Observability

```
Actuator endpoints (no auth required for /health):
  GET /actuator/health    → UP/DOWN
  GET /actuator/metrics   → all Micrometer metrics
  GET /actuator/prometheus → Prometheus scrape format

Micrometer metrics:
  transfer.creation.latency{risk_level}   Timer — core ledger HTTP latency
  risk.evaluation.latency{risk_level}     Timer — deterministic rule engine only
  ai.narrative.latency{outcome}           Timer — async AI path (off critical path)
  idempotency.cache.hit                   Counter
  idempotency.cache.miss                  Counter
  risk.level.count{level}                 Counter — per risk level
  alert.decision.count{decision}          Counter — APPROVED / REJECTED

Structured logs (key=value):
  event=transfer_created transfer_id=... risk_level=... risk_score=... status=...
  event=ai_narrative_saved transfer_id=... risk_level=...
  event=ai_narrative_error transfer_id=... risk_level=... error=...
```

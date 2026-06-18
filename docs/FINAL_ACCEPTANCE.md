# Final Acceptance — Banking Risk Intelligence

## Demo narrative

This system keeps the core banking transaction path deterministic and fast. The transaction service commits ledger-safe state first. The risk engine is deterministic and auditable. HIGH-risk transfers are intercepted before settlement and moved into a pending-review escrow state. AI is used only after commit to generate an analyst narrative, and it receives masked context only. Redis protects idempotency and rate limits client retries, while PostgreSQL remains the final ledger authority.

---

## Correctness checklist

- [x] Transfer API requires auth.
  - `SecurityConfig`: POST `/api/transfers` requires `CUSTOMER` or `ADMIN` role.
  - `SecurityEndpointTest.transferWithoutToken_returns401` verifies 401 on missing token.

- [x] Transfer API requires `Idempotency-Key`.
  - `TransactionService.createTransfer`: throws `MissingIdempotencyKeyException` when header is null or blank.
  - `TransferApiIntegrationTest.missingIdempotencyKey_returns400` verifies 400 response.

- [x] Duplicate retry creates no duplicate ledger rows.
  - `IdempotencyService` returns cached response on repeat key; DB `idx_transfers_request_id` unique index is final guardrail.
  - `TransferApiIntegrationTest.idempotentRetry_returnsSameTransfer` verifies identical transferId and single ledger row.

- [x] Customer cannot access another customer's account.
  - `TransactionService.doTransfer`: checks `source.ownerUserId == principal.userId`; throws `ForbiddenAccountException` on mismatch.
  - `SecurityEndpointTest.customer_cannotDebitUnownedAccount` verifies 403.

- [x] Money stored in minor units only.
  - All `Account`, `Transfer`, and `LedgerEntry` fields use `long amountMinor` (no `double` or `BigDecimal`).
  - `MoneyModelTest` verifies minor-unit arithmetic throughout.

- [x] Account cannot go negative under normal or concurrent requests.
  - `Account.hold()` and `Account.debitAvailable()` throw `IllegalStateException` when amount > available.
  - `TransactionService` acquires `PESSIMISTIC_WRITE` lock on source before every debit.
  - `ConcurrentTransferIntegrationTest` verifies no overdraft under concurrent load.

- [x] HIGH risk does not settle directly.
  - `TransactionService.doTransfer`: HIGH branch calls `source.hold()` only — destination `creditAvailable` is never called.
  - Transfer status is set to `PENDING_REVIEW`, not `POSTED`.

- [x] HIGH risk funds go into held balance.
  - `source.hold(amountMinor)` moves funds from `availableBalanceMinor` to `heldBalanceMinor`.
  - A single `HOLD_DEBIT` ledger entry is recorded.
  - `HighRiskEscrowIntegrationTest` verifies held balance and ledger.

- [x] Approve settles held funds exactly once.
  - `AnalystService.approveAlert`: acquires `PESSIMISTIC_WRITE` lock; checks status is `REVIEW_PENDING`; throws `409 CONFLICT` on double-review.
  - Calls `source.settleHeld()` and `destination.creditAvailable()`.
  - `DoubleReviewRaceIntegrationTest` verifies exactly-once settlement under concurrent approve attempts.

- [x] Reject releases held funds exactly once.
  - `AnalystService.rejectAlert`: same lock/status guard; calls `source.releaseHold()`.
  - `RejectPendingTransferIntegrationTest` verifies held balance returns to available.

---

## Risk engine checklist

All rules are in `RiskRuleEngine.evaluate()`. Verified by `RiskRuleEngineTest` (11 tests).

- [x] Account age under 7 days adds 20.
  - `context.accountAgeDays() < 7` → `ACCOUNT_AGE_UNDER_7_DAYS` +20.

- [x] Hourly velocity over 5000 adds 30.
  - `context.hourlyTransferVelocityMinor() > 500_000` (= $5,000 in minor units) → `HOURLY_VELOCITY_OVER_5000` +30.
  - Velocity includes historical POSTED + PENDING_REVIEW amounts plus the current transfer amount.

- [x] New beneficiary adds 25.
  - `!context.beneficiarySeenBefore()` → `NEW_BENEFICIARY` +25.
  - Checked via `beneficiaries` table keyed on `(ownerUserId, destinationAccountId)`.

- [x] Login anomaly adds 16.
  - `context.loginAnomalyFlag()` → `LOGIN_ANOMALY` +16.
  - Flag is set when any `login_audit` row for the user has `anomaly_flag = true` within the last 24 hours.

- [x] Amount over 10000 adds 10.
  - `context.amountMinor() > 1_000_000` (= $10,000 in minor units) → `AMOUNT_OVER_10000` +10.

- [x] LOW is score under 40.
  - `RiskLevel.fromScore(score)`: `score < 40` → `LOW`.

- [x] MEDIUM is 40 to 79.
  - `score >= 40 && score < 80` → `MEDIUM`.

- [x] HIGH is 80 to 100.
  - `score >= 80` → `HIGH`.

- [x] Score clamps at 100.
  - `Math.min(rawScore, 100)` before level classification.

---

## AI safety checklist

- [x] LOW risk does not call AI.
  - `TransactionService`: event published only when `riskLevel == MEDIUM || riskLevel == HIGH`.
  - `NoAiForLowRiskIntegrationTest` verifies no `AI_PENDING` alert is created for LOW.

- [x] MEDIUM/HIGH AI runs asynchronously.
  - `RiskAlertAiHandler` annotated `@Async("riskAiTaskExecutor")`.
  - Async executor configured in `AsyncConfig` (core=4, max=16).

- [x] AI event runs after transaction commit.
  - `RiskAlertAiHandler` annotated `@TransactionalEventListener(phase = AFTER_COMMIT)`.
  - `AsyncAiAfterCommitIntegrationTest` verifies the handler fires after the ledger transaction commits.

- [x] LLM failure does not rollback ledger.
  - `RiskAlertAiHandler` runs outside any transaction; exception is caught and logged.
  - `AiFailureDoesNotBreakLedgerIntegrationTest` simulates LLM failure and verifies transfer remains `POSTED`.

- [x] Raw PII is not sent to LLM.
  - `PiiMaskingService` replaces account UUIDs with `CUSTOMER_TOKEN_N` and redacts email/phone patterns before building the prompt.
  - `PiiMaskingServiceTest` and `PromptBuilderTest` verify no raw IDs appear in prompt text.

- [x] Token map is not logged or persisted.
  - `PiiMaskingService.mask()` returns only the masked string; the UUID→token map is local to the method and discarded.
  - No `MaskingMap` or equivalent field is written to any entity or log statement.

- [x] Prompt/narrative storage is masked.
  - `RiskAlert.contextSnapshotJson` stores the `RiskContext` (which contains masked beneficiary reference, not raw IDs from prompt).
  - `AlertDetailResponse` omits `contextSnapshotJson` from the API response.

---

## Operations checklist

- [x] Docker Compose works.
  - `docker compose config` validates without error.
  - Services: `postgres:16-alpine`, `redis:7-alpine`, optional `ollama` (profile `ai`).
  - Local demo uses Docker PostgreSQL on host port **5433** because many Windows machines already
    run native PostgreSQL on 5432. The local Spring profile is pinned to `127.0.0.1:5433` via
    `application-local.yml` to avoid accidental connection to the wrong database.

- [x] Tests pass from clean checkout.
  - `./mvnw clean test` → BUILD SUCCESS, Tests run: 116, Failures: 0, Errors: 0, Skipped: 0.
  - Testcontainers spins up ephemeral PostgreSQL + Redis; no local DB needed.

- [x] CI workflow exists.
  - `.github/workflows/ci.yml`: checkout → Java 21 → Maven cache → `mvn test` → jar → Docker build → Trivy scan.

- [x] Trivy scanning exists.
  - CI runs `trivy fs` (filesystem scan) and `trivy image` (Docker image scan).
  - Currently `exit-code 0` (report-only). Change to `exit-code 1` to enforce blocking when ready.

- [x] Actuator health works.
  - `GET /actuator/health` → `{"status":"UP"}` (no auth required).
  - `SecurityEndpointTest.actuatorHealth_isPublic` verifies 200 without token.

- [x] Metrics exist.
  - `MetricsService` wires Micrometer timers and counters for transfer creation, risk evaluation, AI narrative, idempotency cache, and analyst decisions.
  - Endpoints: `/actuator/metrics`, `/actuator/prometheus`.

- [x] Docs are accurate.
  - `README.md` — setup, runbook, environment variables, API examples, risk scoring rules.
  - `docs/ARCHITECTURE.md` — layer diagram, transaction/risk/AI/escrow flows, observability.
  - `docs/TESTING.md` — full test matrix with what-each-test-proves column.

---

## Manual verification SQL

Connect to the running database:
```bash
docker exec -it banking-risk-postgres psql -U banking -d banking_risk
```

### Transfer status distribution
```sql
select status, count(*) from transfers group by status;
```

### Alert risk level distribution
```sql
select risk_level, count(*) from risk_alerts group by risk_level;
```

### Ledger entries per transfer (should never exceed 2 for normal, 1 for HIGH held)
```sql
select transfer_id, count(*) from ledger_entries group by transfer_id;
```

### Idempotency guardrail — duplicate transfers (must return zero rows)
```sql
select request_id, count(*)
from transfers
group by request_id
having count(*) > 1;
```

Expected result: **no rows**. If any rows appear, the idempotency layer has failed.

### Account balance sanity check (no negative balances)
```sql
select id, account_number, available_balance_minor, held_balance_minor
from accounts
where available_balance_minor < 0 or held_balance_minor < 0;
```

Expected result: **no rows**.

### Held funds reconciliation (PENDING_REVIEW transfers should match held balance)
```sql
select
  a.account_number,
  a.held_balance_minor,
  sum(le.amount_minor) as total_holds_in_ledger
from accounts a
join ledger_entries le on le.account_id = a.id
join transfers t on t.id = le.transfer_id
where le.entry_type = 'HOLD_DEBIT'
  and t.status = 'PENDING_REVIEW'
group by a.id, a.account_number, a.held_balance_minor;
```

Expected: `held_balance_minor = total_holds_in_ledger` for each account.

---

## What is not claimed

The following claims are **not made** in this project without measurement:

- `<5ms latency` — benchmark numbers depend on hardware and JVM warmup; see `benchmark/load-test.sh` output.
- `98.3% triage efficiency` — no triage timing study has been conducted.
- `100% duplicate prevention` — the system is designed to prevent duplicates through Redis idempotency plus database uniqueness, and tests verify that duplicate retries do not create duplicate ledger entries. Edge cases under Redis failure are mitigated by the DB unique index guardrail.
- `zero PII leakage` — PII masking tests and prompt inspection verify that raw UUIDs do not appear in AI prompts; a full log audit has not been performed against a running production system.

Safer statement: **The system is designed to prevent duplicate ledger creation through Redis idempotency plus database uniqueness, and tests verify duplicate retries do not create duplicate ledger entries.**

---

## Final acceptance command results

```
Command: ./mvnw clean test
Result:  BUILD SUCCESS
         Tests run: 116, Failures: 0, Errors: 0, Skipped: 0

Command: docker compose config
Result:  Validates without error (postgres, redis, ollama services defined)
```

---

## Local demo UI — observability note

In the local demo UI, `/actuator/metrics` and `/actuator/prometheus` are intentionally protected.
The UI fetches them using the analyst JWT via `Authorization: Bearer <token>`.
Opening those URLs directly in the browser without a token will return:

```json
{"code":"UNAUTHORIZED","message":"Authentication required"}
```

This is expected security behavior, not a bug.

## Local demo UI — recommended demo order

```
1.  Check health                      (Step 1 — confirms backend is live)
2.  Load demo context                 (Step 2 — loads actual seeded account IDs from /dev/demo-context)
3.  Get customer1 token               (Step 3)
4.  Get customer2 token               (Step 3)
5.  Get analyst token                 (Step 3)
6.  Run LOW-risk transfer             (Step 4 — ACCOUNT_AGE_UNDER_7_DAYS → LOW → POSTED)
7.  Run MEDIUM-risk transfer          (Step 4 — new beneficiary + login anomaly → MEDIUM → POSTED)
8.  Run HIGH-risk transfer            (Step 4 — velocity breach → HIGH → PENDING_REVIEW)
9.  List pending alerts               (Step 5 — shows the HIGH-risk alert in escrow)
10. Approve or reject selected alert  (Step 5 — settles or releases held funds)
11. Load authenticated metrics        (Step 6 — uses analyst token to fetch /actuator/metrics etc.)
```

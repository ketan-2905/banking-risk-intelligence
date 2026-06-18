# Testing — Banking Risk Intelligence

## Test matrix

| Category | Class | What it proves |
|----------|-------|----------------|
| **Unit** | `MoneyModelTest` | Account balance mutations (hold, debit, credit, settleHeld, releaseHold) never go negative; minor-unit arithmetic is exact |
| **Unit** | `RiskRuleEngineTest` | Each rule fires at the correct threshold; score accumulates correctly; LOW/MEDIUM/HIGH boundaries |
| **Unit** | `PiiMaskingServiceTest` | UUIDs replaced with stable tokens; email and phone patterns redacted; no leakage into masked output |
| **Unit** | `PromptBuilderTest` | Prompt contains only masked tokens; triggered-rule evidence uses tokens not raw IDs |
| **Unit** | `IdempotencyServiceTest` | Cache-hit returns cached value without calling supplier; lock acquired on miss; lock released in finally block even on failure; concurrent lock-miss waits then re-checks cache |
| **Unit** | `RateLimiterServiceTest` | At-limit allowed; over-limit throws; different users isolated; Redis null → fail-open |
| **Unit** | `RiskContextBuilderTest` | Context fields populated correctly from account + transfer data |
| **Unit** | `OllamaAiGatewayTest` | Disabled gateway returns null without network call; timeout config applied |
| **Integration** | `ApplicationContextTest` | Spring context starts with all beans wired |
| **Integration** | `RepositoryIntegrationTest` | Flyway migrations apply cleanly from empty DB; CRUD on all entities |
| **Integration** | `TransferApiIntegrationTest` | Transfer creates exactly 1 transfer + 2 ledger entries; balances update correctly; idempotency retry returns same ID; missing idempotency key → 400; insufficient funds → 409; wrong owner → 403; no auth → 401; analyst cannot create transfer → 403 |
| **Integration** | `LowRiskTransferIntegrationTest` | Small-amount transfer → POSTED, LOW, no alert |
| **Integration** | `MediumRiskTransferIntegrationTest` | Medium-risk → POSTED, MEDIUM, AI_PENDING alert |
| **Integration** | `HighRiskEscrowIntegrationTest` | High-risk → PENDING_REVIEW, HOLD_DEBIT ledger only, source held balance increases |
| **Integration** | `NoAiForLowRiskIntegrationTest` | LOW-risk transfer does NOT publish AI event |
| **Integration** | `AsyncAiAfterCommitIntegrationTest` | AI handler is invoked only after DB commit, not during transaction |
| **Integration** | `AiFailureDoesNotBreakLedgerIntegrationTest` | AI error leaves transfer and ledger in correct final state; error code saved |
| **Integration** | `ConcurrentTransferIntegrationTest` | Same idempotency key under concurrent requests creates exactly 1 transfer |
| **Integration** | `TransactionRollbackRiskTest` | DB rollback on failure leaves no partial ledger state |
| **Integration** | `AnalystAlertListIntegrationTest` | ANALYST 200; CUSTOMER 403; optional status/riskLevel filters work; response shape correct |
| **Integration** | `ApprovePendingTransferIntegrationTest` | Approve: held → 0, destination credited, 2 new ledger entries, audit row |
| **Integration** | `RejectPendingTransferIntegrationTest` | Reject: held → 0, available restored, HOLD_RELEASE ledger entry, audit row |
| **Integration** | `DoubleReviewRaceIntegrationTest` | Concurrent approve+approve: exactly one 200 + one 409; no duplicate ledger entries; one audit row |
| **Integration** | `CannotReviewPostedTransferTest` | Approve/reject on LOW_NO_AI alert (already posted) → 409 |
| **Security** | `SecurityEndpointTest` | No-token → 401 on transfer, analyst list, analyst approve; CUSTOMER → 403 on all analyst endpoints; ANALYST → 403 on transfer creation; customer cannot debit unowned account; alert list body contains no raw account numbers, emails, or phone numbers; actuator/health is public |
| **Benchmark** | `TransferBenchmarkTest` | Measures p50/p95/p99 core ledger latency (excludes async AI); concurrent requests with unique keys all succeed; DB count matches request count (no duplicates) |

## Running tests

```bash
# Full test suite (excludes @Tag("benchmark") by default)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DOCKER_HOST = "tcp://localhost:2375"
.\mvnw.cmd clean test

# Benchmark only
.\mvnw.cmd test -Pno-default-exclude-benchmarks -Dgroups=benchmark -Dtest=TransferBenchmarkTest

# Single class
.\mvnw.cmd test -Dtest=SecurityEndpointTest

# Skip tests (build jar only)
.\mvnw.cmd package -DskipTests
```

## What each key test proves

### Idempotency (duplicate prevention)
`ConcurrentTransferIntegrationTest` and `retryWithSameIdempotencyKey_returnsSameTransferId_noExtraEntries` in `TransferApiIntegrationTest` together prove that:
- Redis SETNX prevents concurrent execution
- The DB `requestId` UNIQUE index catches any Redis-failure race
- The response is bit-for-bit identical on retry (same transferId)

### Escrow correctness
`ApprovePendingTransferIntegrationTest` checks source.heldBalance == 0 after approve AND destination.availableBalance == expected. These are separate assertions to catch partial-credit bugs.

`DoubleReviewRaceIntegrationTest` uses two threads that both attempt to approve the same alert. It verifies: exactly one HTTP 200, exactly one HTTP 409, exactly one audit row, and no duplicate ledger entries. This proves the PESSIMISTIC_WRITE lock ordering prevents double-settlement.

### PII containment
`PiiMaskingServiceTest` (unit) verifies the masking logic. `SecurityEndpointTest.alertListResponse_doesNotContainEmailPatterns` verifies end-to-end that `contextSnapshotJson` never reaches the HTTP response — the API DTO deliberately omits it.

### Benchmark methodology
`TransferBenchmarkTest` measures HTTP round-trip time from client to server and back. The async AI thread starts after the HTTP response is sent, so AI latency is excluded. To observe AI latency, query `/actuator/metrics/ai.narrative.latency` after enabling Ollama.

## Test infrastructure

- **Testcontainers**: PostgreSQL 16 + Redis 7, one container per test class (reused via static fields)
- **Profile `test`**: rate limit raised to 10,000 req/min; lock-wait reduced to 50ms
- **MockBean `SpringAiGateway`**: AI gateway mocked in tests that don't test the AI path, preventing real HTTP calls to Ollama
- **No developer-local DB required**: all state is created in `@BeforeEach` and torn down between tests

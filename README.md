# Banking Risk Intelligence

A Spring Boot 3 backend that evaluates financial transfer risk, holds high-risk funds in escrow, generates AI narratives (optional), and provides an analyst review workflow. Uses synthetic data only — no real banking APIs, no real PII.

## Architecture summary

- **JWT stateless auth** — CUSTOMER/ADMIN → POST /api/transfers; ANALYST/ADMIN → /api/analyst/**
- **Idempotency** — Redis SETNX lock + 24h response cache; DB `requestId` UNIQUE index as final guardrail
- **Rate limiting** — Redis sliding counter (20 req/min default)
- **Risk pipeline** — deterministic rule engine (account age, velocity, new beneficiary, login anomaly, large amount); no ML
- **Settlement** — LOW/MEDIUM → immediate debit + credit; HIGH → hold-only, pending analyst review
- **Async AI** — optional Ollama narrative generated off-thread after commit; PII masked before prompt
- **Analyst review** — approve (settle held funds) or reject (release hold); double-review protected by pessimistic lock
- **Observability** — Micrometer metrics, structured key=value logs, Spring Boot Actuator

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for layer diagrams and flow details.

## Quick runbook (fresh clone)

```powershell
# 1. Reset local DB (stops containers, wipes volumes, starts fresh)
.\scripts\reset-local-db.ps1

# 2. Run tests (Testcontainers — no local DB needed)
.\mvnw.cmd clean test

# 3. Start app with local seed data
.\scripts\run-local.ps1

# 4. Verify health
curl http://localhost:8080/actuator/health
# → {"status":"UP"}
```

> **Windows port note:** Docker Postgres uses host port **5433** (not 5432) to avoid conflicts
> with any native Windows PostgreSQL service already running on 5432. The `application-local.yml`
> profile pins the app to `127.0.0.1:5433`. Do not use port 5432 for the local app DB.

## Local setup

### Prerequisites
- Java 21 (Temurin recommended)
- Docker Desktop (for PostgreSQL + Redis)
- Maven wrapper (`mvnw`) included

### 1. Start infrastructure

```powershell
.\scripts\reset-local-db.ps1
```

This starts:
- `banking-risk-postgres` — PostgreSQL 16 on **host port 5433** (container port 5432)
- `banking-risk-redis` — Redis 7 on port 6379

Host port 5433 is used to avoid conflicts with native Windows PostgreSQL installations that occupy 5432.

Flyway migrations run automatically on app startup.

### 2. Run tests

```bash
./mvnw clean test
```

Tests use Testcontainers. No local database needed.

### 3. Run the application

```powershell
.\scripts\run-local.ps1
```

The `local` profile enables seed data and DEBUG logging. The `application-local.yml` file
pins the datasource to `127.0.0.1:5433` so the app never accidentally connects to a native
Windows PostgreSQL instance on 5432.

### 4. Verify health

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP"}
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_USER` | `banking` | PostgreSQL user |
| `DB_PASSWORD` | `banking_local_only` | PostgreSQL password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `JWT_SECRET` | dev default | **Change in production** |
| `JWT_EXPIRY_MS` | `3600000` | Token lifetime (1 hour) |
| `RATE_LIMIT_RPM` | `20` | Transfers per user per minute |
| `AI_ENABLED` | `false` | Enable Ollama AI narrative |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `llama3` | Ollama model name |

## Docker Compose commands

```bash
docker compose up -d                 # Start PostgreSQL + Redis
docker compose --profile ai up -d   # Also start Ollama
docker compose down                  # Stop and remove containers
docker compose down -v               # Stop and remove volumes (loses data)
```

## Test commands

```powershell
# Full test suite (excludes benchmarks)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DOCKER_HOST = "tcp://localhost:2375"
.\mvnw.cmd clean test

# Single test class
.\mvnw.cmd test -Dtest=SecurityEndpointTest

# Benchmark tests (slow — not in normal CI)
.\mvnw.cmd test -Pbenchmark -Dgroups=benchmark -Dtest=TransferBenchmarkTest
```

Tests use Testcontainers. Docker must be running. No local database needed.

See [docs/TESTING.md](docs/TESTING.md) for the full test matrix.

## Demo

With the app running in `local` profile, run the end-to-end demo:

```bash
# Bash / Git Bash / WSL (requires jq)
chmod +x scripts/demo.sh
./scripts/demo.sh
```

Or open [docs/demo.http](docs/demo.http) in IntelliJ HTTP Client or VS Code REST Client.

The demo walks through: LOW transfer → MEDIUM transfer → two HIGH transfers → analyst approve → analyst reject → idempotency retry.

The local profile exposes a dev-only token endpoint:
```bash
curl http://localhost:8080/dev/token?user=customer1   # CUSTOMER role (USER_1)
curl http://localhost:8080/dev/token?user=customer2   # CUSTOMER role (USER_2, login anomaly)
curl http://localhost:8080/dev/token?user=analyst     # ANALYST role
```

See [docs/FINAL_ACCEPTANCE.md](docs/FINAL_ACCEPTANCE.md) for the complete acceptance checklist and verification SQL.

## API examples

All requests require `Authorization: Bearer <token>`.

### Create a transfer (CUSTOMER)

```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "<uuid>",
    "destinationAccountId": "<uuid>",
    "amountMinor": 100000,
    "currency": "USD"
  }'
```

Response:
```json
{
  "transferId": "...",
  "status": "POSTED",
  "riskScore": 0,
  "riskLevel": "LOW",
  "message": "Transfer posted successfully"
}
```

### List pending alerts (ANALYST)

```bash
curl http://localhost:8080/api/analyst/alerts?status=REVIEW_PENDING \
  -H "Authorization: Bearer $ANALYST_TOKEN"
```

### Approve an alert (ANALYST)

```bash
curl -X POST http://localhost:8080/api/analyst/alerts/<alertId>/approve \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Verified with customer — legitimate transfer"}'
```

### Check metrics

```bash
curl http://localhost:8080/actuator/metrics/transfer.creation.latency
curl http://localhost:8080/actuator/metrics/risk.level.count
curl http://localhost:8080/actuator/prometheus   # Prometheus scrape format
```

## Risk scoring rules

| Rule | Trigger | Score added |
|------|---------|-------------|
| Account age | Account created < 7 days ago | +20 |
| Hourly velocity | Outgoing transfers in last hour > $5,000 | +30 |
| New beneficiary | Destination not previously seen | +25 |
| Login anomaly | Anomalous login detected in last 24 hours | +16 |
| Large amount | Transfer > $10,000 | +10 |

Score clamps at 100. All amounts are in minor units internally (100 = $1.00).

Risk levels:
- **LOW** (0–39): Immediate settlement, no AI narrative
- **MEDIUM** (40–79): Immediate settlement, async AI narrative queued
- **HIGH** (80–100): Funds held in escrow, analyst review required, async AI narrative

## Security and PII statement

- All data is **synthetic** — no real bank accounts, no real customers
- PII masking replaces account/owner UUIDs with tokens (`CUSTOMER_TOKEN_1`) before any AI prompt
- Email and phone patterns are redacted from AI context
- `contextSnapshotJson` is **never returned** in API responses
- Analyst alert list returns only masked narrative and triggered rule names
- All endpoints require JWT authentication except `GET /actuator/health`

## Benchmark instructions

The benchmark measures **core ledger HTTP latency only**. Async AI completion runs after the HTTP response and is excluded from these numbers.

### Using the shell script (requires `hey`)

```bash
# Install hey: go install github.com/rakyll/hey@latest
export CUSTOMER_TOKEN=<jwt>
export SOURCE_ACCOUNT_ID=<uuid>
export DEST_ACCOUNT_ID=<uuid>
./benchmark/load-test.sh
```

### Using the Java benchmark test

```bash
.\mvnw.cmd test -Pbenchmark -Dgroups=benchmark -Dtest=TransferBenchmarkTest
```

Results are printed to stdout. Latency claims in this project are only made when backed by a measured run — see benchmark output, not this README, for actual numbers.

To observe AI latency separately:
```bash
curl http://localhost:8080/actuator/metrics/ai.narrative.latency
```

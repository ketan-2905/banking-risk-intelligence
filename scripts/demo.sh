#!/usr/bin/env bash
# Banking Risk Intelligence — End-to-End Demo Script
#
# Prerequisites:
#   1. App running with local profile:
#      ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
#   2. jq installed (https://jqlang.github.io/jq/)
#   3. Docker running (PostgreSQL + Redis via: docker compose up -d postgres redis)
#
# On Windows: run inside Git Bash or WSL. For IntelliJ/VS Code, use docs/demo.http instead.
#
# Seeded accounts (created by LocalDataSeeder on first local startup):
#   ACCOUNT_1 (00000000-0000-0000-0000-000000000011): owned by USER_1, $500,000 balance
#     - USER_1 has NO login anomaly
#     - USER_1 has ACCOUNT_2 as a known beneficiary (seeded)
#   ACCOUNT_2 (00000000-0000-0000-0000-000000000012): owned by USER_2, $100,000 balance
#     - USER_2 HAS a login anomaly (seeded)
#     - USER_2 has NO known beneficiaries
#
# Risk scoring rules (deterministic, no ML):
#   ACCOUNT_AGE_UNDER_7_DAYS   +20   (all seeded accounts qualify — created on first run)
#   HOURLY_VELOCITY_OVER_5000  +30   (outgoing transfers in last hour > $5,000)
#   NEW_BENEFICIARY            +25   (destination not previously seen)
#   LOGIN_ANOMALY              +16   (anomaly in last 24 hours)
#   AMOUNT_OVER_10000          +10   (transfer > $10,000)
#   Score clamped at 100.
#   LOW < 40 | MEDIUM 40-79 | HIGH 80-100

set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required. Install using: winget install -e --id jqlang.jq" >&2
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"

ACCOUNT_1="00000000-0000-0000-0000-000000000011"
ACCOUNT_2="00000000-0000-0000-0000-000000000012"

hr() { printf '\n%s\n' "──────────────────────────────────────────────────────────"; }
info() { printf '\n[INFO] %s\n' "$1"; }
pass() { printf '[PASS] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1"; exit 1; }

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  elif command -v python >/dev/null 2>&1; then
    python -c "import uuid; print(uuid.uuid4())"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "import uuid; print(uuid.uuid4())"
  elif command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "[guid]::NewGuid().ToString()" | tr -d '\r' | tr '[:upper:]' '[:lower:]'
  else
    echo "ERROR: no UUID generator found. Install uuidgen, Python, or use PowerShell." >&2
    exit 1
  fi
}

# ─── Token acquisition ───────────────────────────────────────────────────────
hr
info "Acquiring JWT tokens from /dev/token (local profile only)"

CUSTOMER1_TOKEN=$(curl -sf "$BASE_URL/dev/token?user=customer1" | jq -r '.token')
CUSTOMER2_TOKEN=$(curl -sf "$BASE_URL/dev/token?user=customer2" | jq -r '.token')
ANALYST_TOKEN=$(curl -sf  "$BASE_URL/dev/token?user=analyst"   | jq -r '.token')

[ -n "$CUSTOMER1_TOKEN" ] && pass "customer1 token acquired" || fail "Could not get customer1 token"
[ -n "$CUSTOMER2_TOKEN" ] && pass "customer2 token acquired" || fail "Could not get customer2 token"
[ -n "$ANALYST_TOKEN"   ] && pass "analyst token acquired"   || fail "Could not get analyst token"

auth1() { echo "-H \"Authorization: Bearer $CUSTOMER1_TOKEN\""; }
auth2() { echo "-H \"Authorization: Bearer $CUSTOMER2_TOKEN\""; }
authA() { echo "-H \"Authorization: Bearer $ANALYST_TOKEN\""; }

# ─── Scenario 1: LOW-risk transfer ───────────────────────────────────────────
hr
info "SCENARIO 1 — LOW-risk transfer"
info "  USER_1 sends \$5.00 from ACCOUNT_1 to ACCOUNT_2 (known beneficiary)"
info "  Expected rules: ACCOUNT_AGE_UNDER_7_DAYS (+20)"
info "  Expected score: 20 → LOW"
info "  Expected status: POSTED (no AI call)"

LOW_IDEM_KEY="demo-low-$(new_uuid)"

LOW_RESP=$(curl -sf -X POST "$BASE_URL/api/transfers" \
  -H "Authorization: Bearer $CUSTOMER1_TOKEN" \
  -H "Idempotency-Key: $LOW_IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$ACCOUNT_1\",
    \"destinationAccountId\": \"$ACCOUNT_2\",
    \"amountMinor\": 500,
    \"currency\": \"USD\"
  }")

echo "$LOW_RESP" | jq .
LOW_STATUS=$(echo "$LOW_RESP" | jq -r '.status')
LOW_LEVEL=$(echo  "$LOW_RESP" | jq -r '.riskLevel')
LOW_ID=$(echo     "$LOW_RESP" | jq -r '.transferId')

[ "$LOW_STATUS" = "POSTED" ]  && pass "status=POSTED" || fail "Expected POSTED, got $LOW_STATUS"
[ "$LOW_LEVEL"  = "LOW" ]     && pass "riskLevel=LOW" || fail "Expected LOW, got $LOW_LEVEL"

# ─── Scenario 2: MEDIUM-risk transfer ────────────────────────────────────────
hr
info "SCENARIO 2 — MEDIUM-risk transfer"
info "  USER_2 sends \$40.00 from ACCOUNT_2 to ACCOUNT_1 (NEW beneficiary + login anomaly)"
info "  Expected rules: NEW_BENEFICIARY (+25) + ACCOUNT_AGE_UNDER_7_DAYS (+20) + LOGIN_ANOMALY (+16)"
info "  Expected score: 61 → MEDIUM"
info "  Expected status: POSTED + async AI narrative queued"

MED_RESP=$(curl -sf -X POST "$BASE_URL/api/transfers" \
  -H "Authorization: Bearer $CUSTOMER2_TOKEN" \
  -H "Idempotency-Key: demo-medium-$(new_uuid)" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$ACCOUNT_2\",
    \"destinationAccountId\": \"$ACCOUNT_1\",
    \"amountMinor\": 4000,
    \"currency\": \"USD\"
  }")

echo "$MED_RESP" | jq .
MED_STATUS=$(echo "$MED_RESP" | jq -r '.status')
MED_LEVEL=$(echo  "$MED_RESP" | jq -r '.riskLevel')

[ "$MED_STATUS" = "POSTED" ]   && pass "status=POSTED"   || fail "Expected POSTED, got $MED_STATUS"
[ "$MED_LEVEL"  = "MEDIUM" ]   && pass "riskLevel=MEDIUM" || fail "Expected MEDIUM, got $MED_LEVEL"

# ─── Scenario 3: HIGH-risk transfer (for approve demo) ───────────────────────
hr
info "SCENARIO 3 — HIGH-risk transfer (will be APPROVED in step 5)"
info "  USER_2 sends \$5,100.00 from ACCOUNT_2 to ACCOUNT_1"
info "  Velocity from step 2 (\$40.00) + current (\$5,100.00) = \$5,140.00 > \$5,000.00 threshold"
info "  Expected rules: NEW_BENEFICIARY (+25) + ACCOUNT_AGE_UNDER_7_DAYS (+20)"
info "                  + LOGIN_ANOMALY (+16) + HOURLY_VELOCITY_OVER_5000 (+30)"
info "  Expected score: 91 → HIGH"
info "  Expected status: PENDING_REVIEW (funds held, destination NOT credited)"

HIGH1_RESP=$(curl -sf -X POST "$BASE_URL/api/transfers" \
  -H "Authorization: Bearer $CUSTOMER2_TOKEN" \
  -H "Idempotency-Key: demo-high1-$(new_uuid)" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$ACCOUNT_2\",
    \"destinationAccountId\": \"$ACCOUNT_1\",
    \"amountMinor\": 510000,
    \"currency\": \"USD\"
  }")

echo "$HIGH1_RESP" | jq .
HIGH1_STATUS=$(echo "$HIGH1_RESP" | jq -r '.status')
HIGH1_LEVEL=$(echo  "$HIGH1_RESP" | jq -r '.riskLevel')
HIGH1_ID=$(echo     "$HIGH1_RESP" | jq -r '.transferId')

[ "$HIGH1_STATUS" = "PENDING_REVIEW" ] && pass "status=PENDING_REVIEW" || fail "Expected PENDING_REVIEW, got $HIGH1_STATUS"
[ "$HIGH1_LEVEL"  = "HIGH" ]           && pass "riskLevel=HIGH"         || fail "Expected HIGH, got $HIGH1_LEVEL"

# ─── Scenario 4: HIGH-risk transfer (for reject demo) ────────────────────────
hr
info "SCENARIO 4 — HIGH-risk transfer (will be REJECTED in step 6)"
info "  USER_2 sends \$1,000.00 from ACCOUNT_2 to ACCOUNT_1"
info "  Historical velocity: step2 (\$40) + step3 (\$5,100) = \$5,140.00 already > threshold"
info "  Same rules as step 3 — velocity still above \$5,000.00 threshold"

HIGH2_RESP=$(curl -sf -X POST "$BASE_URL/api/transfers" \
  -H "Authorization: Bearer $CUSTOMER2_TOKEN" \
  -H "Idempotency-Key: demo-high2-$(new_uuid)" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$ACCOUNT_2\",
    \"destinationAccountId\": \"$ACCOUNT_1\",
    \"amountMinor\": 100000,
    \"currency\": \"USD\"
  }")

echo "$HIGH2_RESP" | jq .
HIGH2_STATUS=$(echo "$HIGH2_RESP" | jq -r '.status')
HIGH2_LEVEL=$(echo  "$HIGH2_RESP" | jq -r '.riskLevel')
HIGH2_ID=$(echo     "$HIGH2_RESP" | jq -r '.transferId')

[ "$HIGH2_STATUS" = "PENDING_REVIEW" ] && pass "status=PENDING_REVIEW" || fail "Expected PENDING_REVIEW, got $HIGH2_STATUS"
[ "$HIGH2_LEVEL"  = "HIGH" ]           && pass "riskLevel=HIGH"         || fail "Expected HIGH, got $HIGH2_LEVEL"

# ─── List pending alerts ──────────────────────────────────────────────────────
hr
info "Listing REVIEW_PENDING alerts (analyst dashboard)"

ALERTS_RESP=$(curl -sf "$BASE_URL/api/analyst/alerts?status=REVIEW_PENDING" \
  -H "Authorization: Bearer $ANALYST_TOKEN")

echo "$ALERTS_RESP" | jq .

# Extract alert IDs for the two HIGH transfers
HIGH1_ALERT_ID=$(echo "$ALERTS_RESP" | jq -r --arg tid "$HIGH1_ID" \
  '.[] | select(.transferId == $tid) | .alertId' 2>/dev/null || true)
HIGH2_ALERT_ID=$(echo "$ALERTS_RESP" | jq -r --arg tid "$HIGH2_ID" \
  '.[] | select(.transferId == $tid) | .alertId' 2>/dev/null || true)

[ -n "$HIGH1_ALERT_ID" ] && pass "HIGH1 alert found: $HIGH1_ALERT_ID" || fail "HIGH1 alert not found in pending list"
[ -n "$HIGH2_ALERT_ID" ] && pass "HIGH2 alert found: $HIGH2_ALERT_ID" || fail "HIGH2 alert not found in pending list"

# ─── Scenario 5: Analyst APPROVE ─────────────────────────────────────────────
hr
info "SCENARIO 5 — Analyst APPROVE held transfer"
info "  Approving HIGH transfer $HIGH1_ID"
info "  Expected: held funds settle to destination (ACCOUNT_1 credited)"

APPROVE_RESP=$(curl -sf -X POST "$BASE_URL/api/analyst/alerts/$HIGH1_ALERT_ID/approve" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Verified with customer via phone — legitimate business transfer"}')

echo "$APPROVE_RESP" | jq .
APPROVE_DECISION=$(echo "$APPROVE_RESP" | jq -r '.decision')
[ "$APPROVE_DECISION" = "APPROVED" ] && pass "decision=APPROVED" || fail "Expected APPROVED, got $APPROVE_DECISION"

# ─── Scenario 6: Analyst REJECT ──────────────────────────────────────────────
hr
info "SCENARIO 6 — Analyst REJECT held transfer"
info "  Rejecting HIGH transfer $HIGH2_ID"
info "  Expected: held funds return to source (ACCOUNT_2 balance restored)"

REJECT_RESP=$(curl -sf -X POST "$BASE_URL/api/analyst/alerts/$HIGH2_ALERT_ID/reject" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Unable to verify customer — transfer pattern consistent with fraud"}')

echo "$REJECT_RESP" | jq .
REJECT_DECISION=$(echo "$REJECT_RESP" | jq -r '.decision')
[ "$REJECT_DECISION" = "REJECTED" ] && pass "decision=REJECTED" || fail "Expected REJECTED, got $REJECT_DECISION"

# ─── Scenario 7: Idempotency retry ───────────────────────────────────────────
hr
info "SCENARIO 7 — Idempotency retry"
info "  Resending the LOW transfer (step 1) with the same Idempotency-Key"
info "  Expected: same transferId returned, no new ledger entries created"

IDEM_RETRY_RESP=$(curl -sf -X POST "$BASE_URL/api/transfers" \
  -H "Authorization: Bearer $CUSTOMER1_TOKEN" \
  -H "Idempotency-Key: $LOW_IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$ACCOUNT_1\",
    \"destinationAccountId\": \"$ACCOUNT_2\",
    \"amountMinor\": 500,
    \"currency\": \"USD\"
  }")

echo "$IDEM_RETRY_RESP" | jq .
RETRY_ID=$(echo "$IDEM_RETRY_RESP" | jq -r '.transferId')

[ "$RETRY_ID" = "$LOW_ID" ] && pass "Same transferId returned (idempotent)" || \
  fail "Expected transferId=$LOW_ID, got $RETRY_ID"

# ─── Health check ─────────────────────────────────────────────────────────────
hr
info "Actuator health check"
HEALTH=$(curl -sf "$BASE_URL/actuator/health" | jq -r '.status')
[ "$HEALTH" = "UP" ] && pass "Health=UP" || fail "Health check failed: $HEALTH"

# ─── Summary ──────────────────────────────────────────────────────────────────
hr
printf '\n[DONE] All demo scenarios completed successfully.\n\n'
printf 'To verify with SQL (connect to banking-risk-postgres):\n'
printf '  select status, count(*) from transfers group by status;\n'
printf '  select risk_level, count(*) from risk_alerts group by risk_level;\n'
printf '  select request_id, count(*) from transfers group by request_id having count(*) > 1;\n'
printf '  -- Last query should return 0 rows (no duplicate transfers).\n\n'

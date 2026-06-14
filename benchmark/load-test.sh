#!/usr/bin/env bash
# Banking Risk Intelligence — HTTP load test script
#
# Prerequisites:
#   - App running on http://localhost:8080 (docker compose up, or ./mvnw spring-boot:run)
#   - A valid JWT for a CUSTOMER user (generate via /api/auth/token or seed data)
#   - 'hey' installed: https://github.com/rakyll/hey  (go install github.com/rakyll/hey@latest)
#     OR 'wrk' installed: https://github.com/wg/wrk
#
# IMPORTANT: This script measures core ledger HTTP latency only.
# Async AI narrative generation happens off-thread AFTER the response is returned.
# To measure AI latency, query /actuator/metrics/ai.narrative.latency after a run.
#
# Usage:
#   CUSTOMER_TOKEN=<jwt> SOURCE_ACCOUNT_ID=<uuid> DEST_ACCOUNT_ID=<uuid> ./benchmark/load-test.sh
#
# Or export vars in shell then run:
#   export CUSTOMER_TOKEN=$(...)
#   export SOURCE_ACCOUNT_ID=...
#   export DEST_ACCOUNT_ID=...
#   ./benchmark/load-test.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${CUSTOMER_TOKEN:?Set CUSTOMER_TOKEN to a valid JWT for a CUSTOMER user}"
SOURCE="${SOURCE_ACCOUNT_ID:?Set SOURCE_ACCOUNT_ID}"
DEST="${DEST_ACCOUNT_ID:?Set DEST_ACCOUNT_ID}"

CONCURRENCY="${CONCURRENCY:-10}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-200}"

if ! command -v hey &>/dev/null; then
  echo "ERROR: 'hey' not found. Install with: go install github.com/rakyll/hey@latest"
  echo "Or use wrk: see WRKOPTS below"
  exit 1
fi

echo "==================================================================="
echo "Banking Risk Intelligence — Core Ledger Load Test"
echo "  Target: $BASE_URL/api/transfers"
echo "  Concurrency: $CONCURRENCY workers"
echo "  Total requests: $TOTAL_REQUESTS"
echo "  NOTE: Async AI latency is NOT included in these numbers."
echo "==================================================================="

# Generate a unique idempotency key per request using hey's template syntax
# Each worker uses a different key so no two requests share an idempotency key

echo ""
echo "--- LOW-RISK TRANSFER (small amount, unique idempotency keys) ---"
hey -n "$TOTAL_REQUESTS" \
    -c "$CONCURRENCY" \
    -m POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: bench-{{.RequestNumber}}" \
    -d "{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":100,\"currency\":\"USD\"}" \
    "$BASE_URL/api/transfers"

echo ""
echo "==================================================================="
echo "Metrics (measured at app level, including JVM + Micrometer):"
echo "  curl $BASE_URL/actuator/metrics/transfer.creation.latency"
echo "  curl $BASE_URL/actuator/metrics/risk.evaluation.latency"
echo "  curl $BASE_URL/actuator/metrics/ai.narrative.latency"
echo "  curl $BASE_URL/actuator/metrics/idempotency.cache.hit"
echo "  curl $BASE_URL/actuator/metrics/idempotency.cache.miss"
echo "==================================================================="

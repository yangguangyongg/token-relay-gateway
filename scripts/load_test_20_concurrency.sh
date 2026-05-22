#!/usr/bin/env bash
set -euo pipefail

TARGET_URL="${1:-http://localhost:8080/api/health}"
TOTAL_REQUESTS="${2:-200}"
CONCURRENCY="${3:-20}"

if ! [[ "$TOTAL_REQUESTS" =~ ^[0-9]+$ ]] || ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 [target_url] [total_requests] [concurrency]"
  exit 1
fi

TMP_RESULTS="$(mktemp)"
START_TS="$(date +%s)"

seq 1 "$TOTAL_REQUESTS" \
  | xargs -P "$CONCURRENCY" -I{} sh -c \
    "curl -sS -o /dev/null -w '%{http_code}\n' '$TARGET_URL' || echo 000" \
  > "$TMP_RESULTS"

END_TS="$(date +%s)"
DURATION=$((END_TS - START_TS))

SUCCESS_COUNT="$(rg -c '^2[0-9][0-9]$' "$TMP_RESULTS" || true)"
FAIL_COUNT=$((TOTAL_REQUESTS - SUCCESS_COUNT))

echo "Target URL: $TARGET_URL"
echo "Total requests: $TOTAL_REQUESTS"
echo "Concurrency: $CONCURRENCY"
echo "Duration seconds: $DURATION"
echo "2xx success: $SUCCESS_COUNT"
echo "Non-2xx or errors: $FAIL_COUNT"
echo
echo "Status code distribution:"
sort "$TMP_RESULTS" | uniq -c

rm -f "$TMP_RESULTS"

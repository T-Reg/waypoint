#!/usr/bin/env bash
# Rolling restart under load: restart gateway-1 then gateway-2 behind HAProxy and require zero errors.
set -euo pipefail
# shellcheck source=scripts/lib.sh
source "$(dirname "$0")/lib.sh"

"$ROOT/scripts/smoke.sh"
TOKEN="$(customer_token loadtest "weather:read")"
RESULTS="$(mktemp)"
STOP="$(mktemp -u)"
# Stop the load workers however this script ends; otherwise they keep looping against port 8080.
trap 'touch "$STOP"' EXIT
trap 'exit 130' INT TERM

load() {
  while [[ ! -e "$STOP" ]]; do
    printf '%s\n' "$(status_of -H "Authorization: Bearer $TOKEN" "$PUBLIC/weather/load")" >> "$RESULTS"
  done
}
for _ in 1 2 3 4; do load & done

for gw in gateway-1 gateway-2; do
  port=$([[ "$gw" == gateway-1 ]] && echo 18082 || echo 28082)
  echo "restarting $gw"
  dc restart "$gw"
  wait_ready "http://127.0.0.1:$port/actuator/health/readiness"
  sleep 5
done

touch "$STOP"
wait
TOTAL=$(grep -c . "$RESULTS" || true)
ERRORS=$(grep -vc '^200$' "$RESULTS" || true)
echo "requests: $TOTAL, non-200: $ERRORS"
if (( TOTAL < 100 )); then
  echo "FAIL: too few requests to be meaningful" >&2
  exit 1
fi
if (( ERRORS > 0 )); then
  sort "$RESULTS" | uniq -c >&2
  exit 1
fi
echo "rolling restart: zero errors"

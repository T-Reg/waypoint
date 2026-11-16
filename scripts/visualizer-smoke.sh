#!/usr/bin/env bash
# Visualizer smoke test against the running compose stack (run scripts/smoke.sh first).
set -euo pipefail
# shellcheck source=scripts/lib.sh
source "$(dirname "$0")/lib.sh"
VIZ="http://127.0.0.1:8090"

dc --profile viz build visualizer
dc --profile viz up -d --no-build visualizer
wait_ready "$VIZ/actuator/health"

send() {
  curl -s --max-time 15 -X POST "$VIZ/api/requests" -H 'Content-Type: application/json' -d "$1"
}

field() {
  python3 -I -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"
}

expect_eq() {
  local expected="$1" actual="$2" label="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $label: expected $expected, got $actual" >&2
    return 1
  fi
  echo "ok: $label ($actual)"
}

ALLOWED='{"request":{"path":"/weather/today","tenant":"acme","scope":"weather:read"}}'
# Gateways publish each other's keys shortly after start, so the first identity check may race key propagation.
deadline=$((SECONDS + 60))
until [[ "$(send "$ALLOWED" | field "d['upstream']['identity']['verified']")" == "True" ]]; do
  if (( SECONDS > deadline )); then
    echo "FAIL: identity token never verified against the gateway JWKS" >&2
    send "$ALLOWED" >&2
    exit 1
  fi
  sleep 2
done

RESULT="$(send "$ALLOWED")"
expect_eq 200 "$(field "d['status']" <<<"$RESULT")" "allowed request status"
expect_eq upstream "$(field "d['stage']" <<<"$RESULT")" "allowed request reaches the upstream"
expect_eq False "$(field "'Authorization' in d['upstream']['headers']" <<<"$RESULT")" "Authorization stripped before the upstream"
expect_eq True "$(field "d['servedBy'] in ('gateway-1', 'gateway-2')" <<<"$RESULT")" "HAProxy names the serving instance"

DENIED="$(send '{"request":{"path":"/weather/today","tenant":"acme","fault":"MISSING"}}')"
expect_eq 401 "$(field "d['status']" <<<"$DENIED")" "missing token status"
expect_eq authenticate "$(field "d['stage']" <<<"$DENIED")" "missing token stops at Authenticate"
expect_eq False "$(field "d['upstream']['received']" <<<"$DENIED")" "rejected request never reaches the upstream"

PATH_DENIED="$(send '{"request":{"path":"/maps/berlin","tenant":"globex","scope":"weather:read"}}')"
expect_eq allowlist "$(field "d['stage']" <<<"$PATH_DENIED")" "path outside allowlist stops at Allowlist"

HEALTH="$(curl -s "$VIZ/api/health")"
expect_eq "up up" "$(field "' '.join(s['state'] for s in d if s['id'] in ('gateway-1', 'gateway-2'))" <<<"$HEALTH")" "both gateways healthy"
echo "visualizer smoke test passed"

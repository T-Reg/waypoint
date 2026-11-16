#!/usr/bin/env bash
# Container smoke test: start the stack, issue tokens, call the stub upstream, check denials.
set -euo pipefail
# shellcheck source=scripts/lib.sh
source "$(dirname "$0")/lib.sh"

"$ROOT/scripts/dev-keys.sh"
dc build gateway-1
dc up -d --no-build
wait_ready "http://127.0.0.1:18082/actuator/health/readiness"
wait_ready "http://127.0.0.1:28082/actuator/health/readiness"

ACME="$(customer_token acme "weather:read maps:read")"
GLOBEX="$(customer_token globex "weather:read")"
deadline=$((SECONDS + 60))
until [[ "$(status_of -H "Authorization: Bearer $ACME" "$PUBLIC/weather/today")" == "200" ]]; do
  if (( SECONDS > deadline )); then
    echo "FAIL: no successful request through the load balancer" >&2
    dc logs --tail 40 gateway-1 >&2
    exit 1
  fi
  sleep 1
done

expect_status 200 "allowed request reaches upstream with a signed identity" -H "Authorization: Bearer $ACME" "$PUBLIC/weather/today"
expect_status 401 "missing token" "$PUBLIC/weather/today"
expect_status 403 "path outside tenant allowlist" -H "Authorization: Bearer $GLOBEX" "$PUBLIC/maps/x"
expect_status 403 "missing scope" -H "Authorization: Bearer $ACME" -X POST "$PUBLIC/weather/today"
expect_status 400 "ambiguous path" -H "Authorization: Bearer $ACME" --path-as-is "$PUBLIC/weather/..;/maps"
expect_status 401 "admin token on public listener" -H "Authorization: Bearer $(admin_token admin:routes)" "$PUBLIC/weather/today"
expect_status 401 "customer token on admin listener" -H "Authorization: Bearer $ACME" "http://127.0.0.1:18081/admin/routes"
expect_status 200 "admin token on admin listener" -H "Authorization: Bearer $(admin_token admin:routes)" "http://127.0.0.1:18081/admin/routes"
expect_status 200 "JWKS on management listener" "http://127.0.0.1:18082/.well-known/jwks.json"
curl -s "http://127.0.0.1:18082/.well-known/jwks.json" | grep -q '"crv":"Ed25519"'
if curl -s "http://127.0.0.1:18082/.well-known/jwks.json" | grep -q '"d"'; then
  echo "FAIL: private key material in JWKS" >&2
  exit 1
fi
echo "smoke test passed"

#!/usr/bin/env bash
# Shared helpers for the smoke scripts.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PUBLIC="http://127.0.0.1:8080"

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
else
  COMPOSE=(docker-compose)
fi

dc() {
  "${COMPOSE[@]}" --project-directory "$ROOT" --env-file "$ROOT/dev-keys/compose.env" "$@"
}

customer_token() {
  "$ROOT/scripts/token-cli" issue --key "$ROOT/dev-keys/issuer.private.jwk.json" --tenant "$1" --sub smoke --scope "$2" --ttl 15m
}

admin_token() {
  "$ROOT/scripts/token-cli" issue-admin --key "$ROOT/dev-keys/admin.private.jwk.json" --sub smoke-admin --scope "$1" --ttl 10m
}

status_of() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"
}

expect_status() {
  local expected="$1" label="$2"
  shift 2
  local actual
  actual="$(status_of "$@")"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $label: expected $expected, got $actual" >&2
    return 1
  fi
  echo "ok: $label ($actual)"
}

wait_ready() {
  local url="$1" deadline=$((SECONDS + 180))
  until [[ "$(status_of "$url")" == "200" ]]; do
    if (( SECONDS > deadline )); then
      echo "FAIL: timed out waiting for $url" >&2
      dc ps >&2 || true
      dc logs --tail 80 >&2 || true
      return 1
    fi
    sleep 1
  done
}

#!/usr/bin/env bash
# Creates local signing keys and secrets in dev-keys/ (git-ignored). Never overwrites existing files.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/dev-keys"
mkdir -p "$DIR"
chmod 700 "$DIR"

for name in issuer admin; do
  if [[ ! -f "$DIR/$name.private.jwk.json" ]]; then
    "$ROOT/scripts/token-cli" keygen --out-dir "$DIR" --name "$name"
  fi
done

ENV_FILE="$DIR/compose.env"
if [[ ! -f "$ENV_FILE" ]]; then
  secret() { openssl rand -hex 24; }
  umask 077
  cat > "$ENV_FILE" <<EOV
POSTGRES_PASSWORD=$(secret)
MIGRATOR_DB_PASSWORD=$(secret)
APP_DB_PASSWORD=$(secret)
VALKEY_PASSWORD=$(secret)
AUDIT_HMAC_KEY=$(openssl rand -base64 32)
EOV
  echo "wrote $ENV_FILE"
fi
echo "dev keys ready in $DIR"

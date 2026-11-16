# Waypoint: Multi-Tenant API Gateway

A reverse proxy / API gateway that authenticates callers with signed JWTs, routes requests to upstreams that can be changed at runtime, enforces per-tenant policies (path allowlist, per-minute quota, required scopes), and writes a structured audit log entry for every request.

Java 21 · Spring Boot 4.1 (MVC on virtual threads) · PostgreSQL (shared config) · Valkey (shared quotas) · Ed25519 JWTs.

## Architecture

```
Client → normalize path → verify JWT → allowlist → match route → method/scopes → quota → proxy → upstream
                                                                    every outcome → audit log
```

- Three listeners: **public** (8080, proxy only), **admin** (8081, admin API, operator network only) and **management** (8082: JWKS, health, status).
- Routes and tenants live in PostgreSQL. Every instance serves from an in-memory snapshot, which is updated via `LISTEN/NOTIFY`, with polling as a fallback.

See the [request flow diagram with failure branches](docs/architecture.md) and the [design](docs/design.md).

## Setup

Requires Java 21 and Docker (with Compose).

```bash
./scripts/dev-keys.sh                 # signing keys + random secrets in dev-keys/ (git-ignored)
docker compose --env-file dev-keys/compose.env build gateway-1
docker compose --env-file dev-keys/compose.env up -d
```

This starts PostgreSQL, Valkey, two gateways behind HAProxy (`localhost:8080`) and a stub upstream. The seed in `deploy/seed.yaml` creates the routes `/weather` and `/maps` and the tenants `acme`, `globex`, `initech` and `loadtest`. It's applied only to an empty database. Gateway 1's admin and management listeners are on `localhost:18081` and `localhost:18082`.

To run a single gateway without Compose, see the `gateway.*` settings in `src/main/resources/application.yml`. They're all overridable by environment variable, e.g. `DATABASE_URL`, `VALKEY_URL`, `GATEWAY_AUDIT_HMAC_KEY` and `GATEWAY_CUSTOMER_PUBLIC_JWK_FILE`.

## Visualizer

A demo client with a live architecture view. It has one-click scenarios (a burst against the rate limit, shared quota across instances, token attacks, path tricks, header spoofing and more), a custom request composer, and an animated diagram showing where each request stopped. It also shows what the upstream received and the health of every service.

```bash
docker compose --env-file dev-keys/compose.env --profile viz build gateway-1 visualizer
docker compose --env-file dev-keys/compose.env --profile viz up -d     # then open http://127.0.0.1:8090
```

On DigitalOcean it runs as a component of the app at `https://<app>.ondigitalocean.app/visualizer/` (password-protected) and sends its requests through the real App Platform load balancer. See [docs/visualizer.md](docs/visualizer.md) and [docs/deployment.md §9](docs/deployment.md#9-visualizer).

## Token issuance

Tokens are Ed25519 JWTs (`alg: Ed25519`). The bundled `token-cli` holds the issuer's private key; the gateway only ever gets the public key.

```bash
# Customer token for a caller in tenant acme
./scripts/token-cli issue --key dev-keys/issuer.private.jwk.json \
    --tenant acme --sub web-app --scope "weather:read maps:read" --ttl 15m

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/weather/today
```

The CLI is a development stand-in for an identity provider. In production, tenants would get tokens from an OAuth 2.0 token service, and the gateway would read that service's JWKS instead of a key file.

## Managing routes and tenants

The admin API is on its own listener and needs an admin JWT. Admin JWTs are signed with a separate key and carry scopes `admin:routes`, `admin:tenants` or `admin:keys`. Every change is recorded with the admin's identity, and updates use optimistic locking (`If-Match`).

```bash
ADMIN=$(./scripts/token-cli issue-admin --key dev-keys/admin.private.jwk.json --sub alice --scope "admin:routes admin:tenants")

curl -X PUT -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  http://localhost:18081/admin/routes/cities -d '{
    "pathPrefix": "/cities",
    "upstreams": [{"url": "https://cities.internal.example"}],
    "requiredScopes": {"GET": ["cities:read"], "POST": ["cities:write"]}
  }'
# Update: repeat with  -H 'If-Match: "1"'  (the ETag from the previous response)
```

`POST /admin/keys/rotate` rotates the identity-token signing keys on every instance. Each new key is published right away and starts signing after a short grace period (`gateway.identity.key-publish-grace`, default 10s), so upstreams can fetch it before they see it.

## What upstreams receive

Customer tokens never leave the gateway. Each forwarded request carries `X-Gateway-Identity`, a 60-second token signed by the gateway and bound to that one upstream (`aud`). It contains the tenant, caller and only the scopes relevant to that route.

Upstreams should:
- Verify it against the gateway's public keys at `/.well-known/jwks.json` on the management listener.
- Fetch keys only from that fixed URL over TLS, never from a URL or key named inside the token.
- Cache the keys, and refetch when they see an unknown `kid`, since keys rotate.

## Deploying to DigitalOcean

`.do/app.yaml` runs two gateways and a demo upstream on App Platform, with managed PostgreSQL and Valkey. App Platform terminates TLS, and only the public listener is exposed. Step-by-step setup is in [docs/deployment.md](docs/deployment.md).

## Running tests

```bash
./mvnw verify                               # unit, property, integration, multi-instance resilience, coverage gate
./mvnw verify -DexcludedGroups=resilience   # skip the multi-instance suite
./scripts/smoke.sh                          # container smoke test against the Compose stack
./scripts/compose-smoke.sh                  # rolling restart of both gateways under load; requires zero errors
./mvnw -f visualizer/pom.xml verify         # visualizer tests and its coverage gate
./scripts/visualizer-smoke.sh               # visualizer against the running Compose stack (after smoke.sh)
```

Integration tests use Testcontainers (PostgreSQL, Valkey, Toxiproxy) and WireMock. The test catalog is in [docs/testing.md](docs/testing.md).

## CI

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs on every push:
- the full test suite with the coverage gate, for the gateway and the visualizer;
- an OSV dependency scan;
- Docker builds of both images;
- the container smoke test, including the visualizer end to end;
- the rolling-restart test.

Actions are pinned to commit SHAs.

## Keeping data private from the gateway

The gateway forwards request and response bodies byte-for-byte and never logs them. By default the audit log records only the matched route prefix and a keyed hash of the path, not the raw path. To keep sensitive values hidden even from the gateway:
- Keep them out of URL paths and query strings.
- Encrypt request bodies to your upstream's public key.

## Security assumptions and known gaps

- TLS is terminated in front of the gateway. Connections to upstreams, PostgreSQL and Valkey should use TLS in production; the local Compose stack doesn't.
- Individual tokens can't be revoked before they expire, so token lifetimes are kept short. Disabling a tenant revokes all of its tokens.
- Tokens are issued by hand with a CLI and a file-based key. This stands in for a real token service: no self-service issuance, no revocation, and no key held in a hardware or cloud key store.
- Rate limits are shared across instances through Valkey. If Valkey is unavailable, each instance enforces an approximate share of the quota instead of failing.
- DigitalOcean Managed Valkey has no ACL users, so the deployed gateway uses the `default` user on a cluster dedicated to it.
- On App Platform, the audit log records the platform's ingress IP rather than the client IP, which arrives in a header the gateway doesn't read yet (`do-connecting-ip`).
- SSRF protection re-checks an upstream's resolved addresses before every call, but there's no hook at the exact moment of connection, so a narrow DNS-rebinding window remains.
- Upstream responses have a per-route idle timeout but no total deadline, so streaming routes (SSE, long-poll) need a timeout longer than their heartbeat interval.
- A total database outage longer than about 8.5 minutes (with default settings) makes instances report not-ready.
- The gateway operator configures which scopes are forwarded to each upstream. Upstreams can't yet declare the scopes they need themselves.
- Audit logs go to stdout. There's no tamper-evident store yet.
- The visualizer can mint customer tokens for its allowlisted tenants. When hosted, it holds a separate demo key (revocable on its own) and requires a password.
- The DigitalOcean demo deployment turns on `X-Gateway-Instance`, which names the instance that served each response. It's off by default.

The full threat model is in [security_posture.md](security_posture.md).

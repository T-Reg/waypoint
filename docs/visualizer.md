# Visualizer

A demo client and live view of the gateway stack (`visualizer/`). You can send real requests through the load balancer (HAProxy locally, the App Platform load balancer on DigitalOcean), or straight to a gateway, and watch each request move through the pipeline. It shows the stage that rejected a request, what the upstream received, and the health of every service.

It's a separate Spring Boot app (Java 21, no frontend build step). Everything it shows comes from what any client or operator can already observe. The one gateway feature it relies on is optional: with `GATEWAY_EXPOSE_INSTANCE_HEADER=true`, each gateway names itself in `X-Gateway-Instance` (off by default; needed on App Platform, where the load balancer doesn't say which instance answered).

It runs in two profiles: `compose` (default, the local stack) and `app-platform` (a component of the DigitalOcean app, see [deployment.md §9](deployment.md#9-visualizer)).

## Running it locally

```bash
./scripts/dev-keys.sh       # also adds VISUALIZER_ISSUER_KEY to dev-keys/compose.env
docker compose --env-file dev-keys/compose.env --profile viz build gateway-1 visualizer
docker compose --env-file dev-keys/compose.env --profile viz up -d
open http://127.0.0.1:8090
```

The `viz` profile adds one container to the normal stack. Without the profile, the stack is unchanged.

## What's on the page

- **Scenarios:** one-click demos. Each one says what to watch and what result to expect.
- **Custom request:**
  - choose the method, path, tenant, scopes, token fault, extra headers and body;
  - choose a target: the load balancer, `gateway-1` or `gateway-2`;
  - choose a count and concurrency, for your own bursts.
- **Architecture:** Client → load balancer → two gateway lanes (one chip per pipeline stage) → upstream, with Audit, PostgreSQL and Valkey in between. Lanes are claimed by gateway instances as they appear, so App Platform's per-deploy instance IDs show up by name.
  - Each request animates along its real path and stops, in red, at the stage that rejected it.
  - The badges count rejections per stage in the current run.
  - The dots show live health.
- **Run panel:** expected vs actual status counts, the split by serving instance, and one square per request in completion order. In a burst, the strip turns from green to red at the moment the quota runs out.
- **Request log and detail drawer:**
  - the response, its problem `code`, and `Retry-After` / `Allow`;
  - **what the upstream saw**: either nothing, or the URL and headers it received;
  - the `X-Gateway-Identity` claims, verified against the gateway's JWKS.

| Scenario | Shows |
|---|---|
| Allowed request | Every stage passes. The upstream gets a gateway-signed identity, and `Authorization` is stripped. |
| Burst: rate limiting | Globex (60/min) sends 80 requests at once: 60 × 200, then 429 with `Retry-After`. |
| Shared quota across instances | Initech (30/min) sends 25 requests to each gateway directly and in parallel. Exactly 30 pass in total. |
| Load balancing | 12 paced requests alternate between `gateway-1` and `gateway-2`. |
| Missing token, Forged tenant, Token attacks, Unknown tenant | Authentication: missing, tampered payload, expired, `nbf` in the future, wrong `aud`, `alg=none`, HS256 key confusion, unknown `tid`. |
| Policy denials | Allowlist (403), Scope (403) and Method (405 with `Allow`). |
| Path traversal tricks | `..;`, `%2e%2e`, `%252e` and `%2F` are rejected at Normalize (400). |
| Header spoofing | Forged `X-Gateway-Identity` / `X-Forwarded-For` / `Forwarded` headers are replaced before the upstream sees them. |
| Full tour, Mixed traffic | Requests stopping at successive stages, and steady mixed load from several tenants. |

Tenants `acme`, `globex`, `initech` and `loadtest` come from `deploy/seed.yaml`. `umbrella` is deliberately missing from the seed.

## How it knows where a request stopped

1. **Stage:** every gateway rejection is RFC 9457 problem+json with a `code`, and each code belongs to exactly one stage (`StageMapper`, design.md §6).
2. **Instance:** the gateway's `X-Gateway-Instance` when it's enabled; otherwise HAProxy's `X-Served-By`. Direct targets name themselves.
3. **Upstream:** the visualizer asks the stub upstream's request journal (WireMock `/__admin/requests/find`) for the `X-Request-Id` the gateway injected. This shows that rejected requests never reach the upstream, and what allowed ones looked like.
4. **Identity:** the `X-Gateway-Identity` token the upstream received is verified the way a real upstream would. The visualizer fetches the gateway's JWKS from a fixed URL, caches it, and refetches on an unknown `kid` at most every 5 s.
5. **Health:** every 1.5 s the visualizer polls the gateways' `/internal/status`, the load balancer, and WireMock's health endpoint.
   - Gateway instances are keyed by the `instanceId` their status reports. On App Platform, `http://gateway:8082` load-balances across instances, so each poll sends 4 probes on fresh connections and remembers instances seen in the last 10 s.
   - Locally the load balancer's state comes from HAProxy's read-only stats (`:8404`, not published to the host); on App Platform from a request through the public URL.
   - It has no PostgreSQL or Valkey credentials.
   - Their status is what the gateways report: config store UP/DOWN, and rate limiter normal/degraded.

The animation replays each request from its result, after the response arrives. The audit node pulses for every request because the gateway audits every outcome; the visualizer doesn't read the audit log.

## Security model

The visualizer can mint customer tokens for any tenant on its allowlist. Treat it like `token-cli`, not like a public website.

- Locally it uses the dev issuer key. **On DigitalOcean it holds only a separate demo key** that the gateway trusts alongside the real issuer key (a JWK set). The real issuer key never leaves the operator's machine, and removing the demo key from the set revokes the visualizer.

- It only mints for `visualizer.allowed-tenants`, with a TTL of at most `visualizer.max-ttl` (5 min by default; demo tokens last 2 min).
- `VISUALIZER_ACCESS_PASSWORD` turns on HTTP Basic for everything except `/actuator/health`. With `VISUALIZER_ACCESS_REQUIRED=true` startup fails without a password; the `app-platform` profile sets it.
- Runs are capped: at most 200 requests per run, 20 in flight, and 3 concurrent runs.
- The identity token's signature is removed before it reaches the browser. The page shows the claims, never a replayable token.
- Locally it runs on its own `client` network with HAProxy, plus `backend`, but **not** on `edge`. The gateways trust `X-Forwarded-For` from `edge`, and the visualizer is a client like any other. On App Platform its requests go out through the public load balancer like any client's.

## Configuration

Profile defaults are in `visualizer/src/main/resources/application-compose.yml` and `application-app-platform.yml`. Each value can be overridden with an environment variable:

| Variable | `compose` (default) | `app-platform` |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | unset | `app-platform` |
| `VISUALIZER_ISSUER_KEY` / `VISUALIZER_ISSUER_KEY_FILE` | inline JWK / `/keys/issuer.private.jwk.json` | demo private JWK (secret) |
| `VISUALIZER_LB_URL` | `http://haproxy:8080` | required: `${APP_URL}` |
| Other targets | `gateway-1`, `gateway-2` (`VISUALIZER_GATEWAY_{1,2}_URL`) | `internal` = `http://gateway:8080` |
| Gateway status | `VISUALIZER_GATEWAY_{1,2}_MANAGEMENT_URL` | `VISUALIZER_GATEWAY_MANAGEMENT_URL` = `http://gateway:8082` |
| `VISUALIZER_JWKS_URL` | gateway-1's JWKS | `http://gateway:8082/.well-known/jwks.json` |
| `VISUALIZER_HAPROXY_STATS_URL` | `http://haproxy:8404/stats;csv` | none |
| `VISUALIZER_UPSTREAM_ADMIN_URL` | `http://upstream:8080/__admin` | same |
| `VISUALIZER_ALLOWED_TENANTS` | `acme,globex,initech,loadtest,umbrella` | same |
| `VISUALIZER_ACCESS_REQUIRED`, `_USERNAME`, `_PASSWORD` | `false`, `demo`, empty | `true`, `demo`, secret |
| `PORT` | `8090` | `8090` |

On App Platform it's served under `/visualizer/` (the ingress strips the prefix). The page uses relative URLs and adds the trailing slash itself.

## Limitations

- The diagram has two gateway lanes. A third instance's requests are drawn on a shared lane.
- A request the load balancer itself rejects (e.g. no healthy backend) shows as stopping at the load balancer.
- On App Platform, instances can't be addressed individually, so the "Shared quota across instances" scenario is left out. The burst scenario shows the same thing: its requests are spread across instances, yet only 60 pass.
- Against a real upstream instead of WireMock there's no request journal. The page then says "journal unavailable" and still places requests from the gateway's response.
- There are no automated browser tests. Tests cover the API, token minting, stage mapping, the upstream journal, health and the scenario runner. The page itself was checked by driving headless Chrome.

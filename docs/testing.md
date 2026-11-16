# Test Plan

Test cases referenced from [design.md](design.md) and [security_posture.md](../security_posture.md). Test rules (coverage gate, determinism, required categories) live in [CLAUDE.md](../CLAUDE.md#testing-rules).

Run everything with `./mvnw verify`. Surefire picks up `*Test` (unit), `*Properties` (jqwik) and `*IT` (Testcontainers integration and `resilience`-tagged) classes, and JaCoCo enforces line ≥ 90% and branch ≥ 85%. To skip the multi-instance suite locally, add `-DexcludedGroups=resilience`.

## Where each area is tested

| Area | Tests (under `src/test/java/dev/waypoint/gateway/`) |
|---|---|
| Path normalization, prefix matching | `path/PathNormalizerTest`, `path/PathNormalizerProperties`, `path/PathPrefixTest`, `path/PrefixMatcherProperties` |
| Token validation (every rejection branch) | `auth/Ed25519JwtVerifierTest`, `auth/CustomerAuthenticatorTest`, `auth/AdminAuthenticatorTest`, `auth/JwkLoaderTest` (incl. separate admin/customer trust) |
| Policy stages (allowlist, 404/405/403/429, ordering) | `pipeline/StagesTest`, `it/GatewayProxyIT` |
| Routes, tenants, SSRF validation | `routing/*Test`, `tenant/TenantValidatorTest`, `it/AdminApiIT` |
| Proxy headers, client IP, streaming, 502/504/413 | `proxy/*Test`, `it/GatewayProxyIT` |
| Upstream body idle timeout and mid-body failures: steady body longer than `timeoutMs` streams fully; stall or drop before commit → 504/502 without upstream headers; stall or drop after commit → connection aborted with no terminating chunk and the audit `error_code` set | `proxy/IdleTimeoutRelayTest`, `it/GatewayProxyIT` (raw-socket `support/StallingUpstream`) |
| Rate limiting | `ratelimit/LocalRateLimiterTest`, `ratelimit/LocalRateLimiterProperties`, `ratelimit/ResilientRateLimiterTest`, `ratelimit/ValkeyRateLimiterIT`, `ratelimit/ValkeySharedQuotaProperties` |
| Audit log | `audit/AuditTest`, audit assertions in `it/GatewayProxyIT` and `it/AdminApiIT` |
| Identity token & JWKS | `identity/*Test`, `identity/IdentityTokenProperties`, `it/IdentityIT`, `it/GatewayProxyIT` |
| Config store, migrations, least privilege | `store/PostgresConfigStoreIT`, `store/SnapshotSwapProperties` |
| Token CLI | `cli/TokenCliTest` |
| Multi-instance R1–R20 | `resilience/ConfigPropagationIT` (R1–R5, R13, R14), `resilience/DatabaseOutageIT` (R6, R7, R20), `resilience/LifecycleIT` (R8–R12, R15), `resilience/SharedQuotaIT` (R16–R19) |

## Identity token & JWKS

Design: [identity.md §2](identity.md#2-identity-propagation-to-upstreams).

| Level | Test |
|---|---|
| Unit | JWKS contains only public parameters: assert no `d` on every key, and that the JSON doesn't contain the private-key bytes. |
| Unit | Each key has `kid`, `kty=OKP`, `crv=Ed25519`, `alg=Ed25519`, `use=sig`. |
| Unit | The minted token's header `kid` equals the current key's `kid`, and it verifies against the published JWKS. |
| Unit | Verification fails for: a tampered payload, a token signed by a key not in the JWKS, an expired token, the wrong `aud`, the wrong `iss`, the wrong `typ`, and `alg` other than Ed25519. |
| Unit | Verification ignores `jku`/`x5u`/`jwk` headers. A token pointing at an attacker key is rejected. |
| Unit | Rotation: right after `rotate()`, both keys are published and new tokens use the new `kid`. After TTL + skew (fake clock), the old key is gone. |
| Property (jqwik) | For arbitrary `tid`/`sub`/`scope`, mint → verify via JWKS round-trips the claims exactly. |
| Property (jqwik) | Flipping any single byte of a minted token makes verification fail. |
| Integration | `GET /.well-known/jwks.json` on the management listener returns 200 with the right content type and cache header. On the admin listener it returns 404. The public listener never serves it: unauthenticated callers get 401 (auth runs first), and an authenticated tenant whose allowlist covers the path gets 404. |
| Integration | Full path: client → gateway → WireMock upstream. The test captures `X-Gateway-Identity`, fetches the JWKS **from the running gateway**, and verifies the token with Nimbus `JWKSource` exactly as a real upstream would. It asserts `tid`, `sub`, `scope`, `aud = route.audience`, `exp ≤ 60s`, and `rid` equal to `X-Request-Id`. |
| Integration | Audience binding: a token captured from route A is rejected by a verifier configured for route B's audience. |
| Integration | Rotation end to end: capture a token, call `POST /admin/keys/rotate`, capture a second token. Both verify during the overlap, and the second has the new `kid`. A verifier with the old cached JWKS refetches on the unknown `kid` and succeeds. |
| Integration | Rotate without an admin JWT returns 401, with a customer token returns 401, and with an `admin:routes`-only token returns 403. Through the public listener, an admin token gets 401 and an allowlisted customer token gets 404. |
| Integration | Spoofing: a client-sent `X-Gateway-Identity` is stripped and replaced, and `Connection: X-Gateway-Identity, X-Request-Id` doesn't remove the gateway's headers. The upstream receives only the gateway-minted token. |
| Integration | Rejected requests (401/403/429) mint no identity token, and the upstream receives zero requests. |

## Multi-instance resilience (R1–R20)

Design: [multi-instance.md](multi-instance.md).

Conventions: JUnit tag `resilience`; Testcontainers PostgreSQL + Valkey (+ Toxiproxy for R19, R20); 2–3 gateway Spring contexts in one JVM on random ports against the same DB; Awaitility with bounded timeouts, never `Thread.sleep`.

| # | Scenario | Asserts |
|---|---|---|
| R1 | **Persistence across restart:** add a route via instance A, stop A, start A′ | A′ serves the route; `config_audit` has the entry |
| R2 | **Seed doesn't clobber:** change a seeded route at runtime, restart with `seedIfEmpty` | The runtime value survives |
| R3 | **Propagation:** create, update and delete a route on A | B reflects each change within the bound; both report the same `revision` |
| R4 | **Missed notification:** kill B's `LISTEN` backend (`pg_terminate_backend`), then write on A | B converges via poll and re-establishes `LISTEN` |
| R5 | **Concurrent admin writes:** A and B `PUT` the same route with the same `If-Match` | Exactly one 200 and one 409; no lost update; the audit has one change |
| R6 | **DB outage while running:** pause the Postgres container | All instances keep proxying with the last snapshot (zero failed requests); identity tokens still verify against a JWKS fetched during the outage; admin writes return 503; after unpause, a write converges everywhere |
| R7 | **DB down at startup:** start C while Postgres is paused | C stays not-ready and serves no traffic; on unpause, C becomes ready with the current snapshot |
| R8 | **Scale-up:** start C against a DB with existing config | C is not ready until its snapshot is loaded; its first proxied request uses the correct route; C's public key appears in the JWKS served by A and B |
| R9 | **Graceful scale-down:** a slow upstream request (WireMock delay) is in flight on A; send SIGTERM / close A's context | The in-flight request completes 200; A reports not-ready immediately; A's key stays in the JWKS until TTL + skew, then disappears |
| R10 | **Crash:** abruptly stop B without the shutdown hooks | A unaffected; B's key leaves the JWKS after the heartbeat timeout |
| R11 | **Cross-instance identity:** a token minted by A | Verifies using a JWKS fetched from B (and from C after scale-up) |
| R12 | **Rotation broadcast:** `POST /admin/keys/rotate` on A | Every instance rotates; all new `kid`s are published by every instance; old keys are kept during the overlap |
| R13 | **Bad entry:** insert an invalid route row directly in the DB and bump the revision, then restart one instance | Running and restarted instances serve every other route; only the bad route's prefix returns 503; the restarted instance becomes ready |
| R14 | **Concurrent migrations:** start A, B and C at the same moment on an empty DB | All three become ready; the schema is migrated exactly once |
| R15 | **Zero-downtime rolling restart (headline test):** continuous client load through an in-test round-robin "LB" that polls readiness on an interval (like a real LB), while A, B and C restart one at a time and an admin change lands mid-roll | **Zero** non-2xx responses; every instance ends on the same `revision`; no config lost |
| R16 | **Shared quota:** tenant with RPM = 60; fire 100 requests spread round-robin over A, B and C (Testcontainers Valkey) | Exactly 60 allowed in total, 40 get 429 with a correct `Retry-After`; the upstream received exactly 60. Exact counts rely on the sliding-window algorithm ([design.md §6](design.md#6-policy-enforcement)) |
| R17 | **Scale-up/down doesn't reset quota:** use 50 of 60, start C, stop B, send 20 more | Only 10 more are allowed |
| R18 | **Valkey outage:** pause the Valkey container under load | Zero 5xx; the limiter is degraded to RPM ÷ live instances; the `ratelimit.degraded` metric is set; after unpause, shared counting resumes automatically |
| R19 | **Valkey slow:** add latency above the command timeout (Toxiproxy) | Requests aren't slowed beyond the timeout; the limiter degrades rather than blocking |
| R20 | **Partition:** cut only B's DB connection (Toxiproxy) | B keeps serving; tokens minted by B verify via A's JWKS. B reports not-ready once its heartbeat is stale for `key-stale-after − TTL − skew`; at that moment A still publishes B's key, and A drops it only after `key-stale-after` |

## Container smoke test (`compose-smoke` CI job)

`scripts/smoke.sh` (CI job `container-smoke`) starts the stack, issues tokens with `token-cli`, and checks: an allowed request reaches the stub upstream (which returns 400 unless it gets a well-formed `X-Gateway-Identity` and no `Authorization`), plus 401/403/400 denials, listener separation, and that the JWKS holds only public keys.

`scripts/compose-smoke.sh` (CI job `compose-smoke`): docker compose runs Postgres, Valkey, 2 gateways and HAProxy as the LB. HAProxy is used instead of open-source nginx because it has active health checks, and nginx's are [NGINX Plus only](https://docs.nginx.com/nginx/admin-guide/load-balancer/http-health-check/). The job runs a load loop with `docker compose restart gateway-1`, then `gateway-2`, and asserts no errors.

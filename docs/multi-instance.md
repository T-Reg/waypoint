# Multi-Instance Operation

Part of the [design](design.md). Covers the shared PostgreSQL config store, propagation, lifecycle (reboot, scale-up, scale-down) and shared rate limiting. Test cases R1–R21 are in [testing.md](testing.md#multi-instance-resilience-r1r21).

## 1. Shared config store

**Goal:** reboots, scale-up and scale-down lose no config and drop no requests. Any admin change reaches every instance within a bounded time.

**Source of truth:** PostgreSQL. The request path **never** queries the DB. It reads the in-memory `RouteTable` / tenant snapshot ([design.md §5](design.md#5-dynamic-routing)), so a DB outage doesn't stop traffic.

**Schema** (Flyway migrations):

| Table | Contents |
|---|---|
| `routes` | Route fields ([design.md §5](design.md#5-dynamic-routing)) plus `version` (optimistic lock), `updated_at`, `updated_by` |
| `tenants` | Tenant policy plus `version`, `updated_at`, `updated_by` |
| `config_revision` | A single row with a monotonically increasing `revision`, bumped in the same transaction as every config write |
| `config_audit` | An append-only record of every admin change (the app user may only `INSERT`/`SELECT`): who, when, what, and before/after values |
| `key_rotation` | A single row with the key-rotation `generation`, bumped by `POST /admin/keys/rotate` |
| `gateway_keys` | `kid`, `public_jwk`, `instance_id`, `created_at`, `last_heartbeat` (every 10s), `retired_at`. Public keys only (see [identity.md §2](identity.md#2-identity-propagation-to-upstreams)). Rows retired, or not heartbeated, for more than 1 day are deleted. |

**Admin write path** (any instance can serve it):
1. Validate the change (same rules as [design.md §5](design.md#5-dynamic-routing): URL validation, prefix syntax).
2. In one transaction: update the row with `WHERE version = :expected`, bump `config_revision`, insert into `config_audit`, and run `NOTIFY config_changed, '<revision>'`.
3. If no row matched, the update conflicted: return **409 Conflict**. Admin `PUT` uses `If-Match: <version>` / `ETag`, so two admins on different instances can't silently overwrite each other.
4. If the DB is unreachable, return **503** without changing anything. Writes never half-apply.

**Propagation to every instance:**
- **Push:** each instance holds a dedicated `LISTEN gateway_events` connection (`application_name = waypoint-listen-<instance>`). Payloads are `config:<revision>`, `keys:<generation>` (a rotation) and `key:<kid>` (a key was registered, sent in the same statement as the insert). Every payload triggers the same refresh, which also reloads peers' public keys. It's a direct (or session-mode) connection, because `LISTEN` doesn't work through PgBouncer transaction pooling ([PgBouncer features](https://www.pgbouncer.org/features.html)), which DigitalOcean connection pools use. On a notification with a revision newer than its own, it reloads the full snapshot in one read-only transaction, validates it, builds a new table and swaps it in atomically ([design.md §5](design.md#5-dynamic-routing)).
- **Poll fallback:** every `gateway.store.poll-interval` (default 10s), the instance compares its revision against the `config_revision` row. It also checks the key generation and validates the LISTEN connection periodically, since a half-open TCP connection would otherwise wait forever. This covers missed notifications, a dropped `LISTEN` connection, or DB failover. The `LISTEN` connection reconnects with backoff.
- **Bound:** propagation normally takes milliseconds (push). The worst case is the poll interval. Rotated identity keys wait `key-publish-grace` (default: the poll interval) before signing, so that worst case is covered for them too ([identity.md](identity.md#jwks-endpoint-in-scope)).
- **Bad entries:** each route and tenant is validated individually.
  - An invalid entry is skipped, and its prefix fails closed with 503. The rest of the snapshot loads normally, and the gateway logs an error and increments a metric.
  - This applies at startup too, so one bad row can't stop new instances from becoming ready. The lesson comes from [Cloudflare's 18 Nov 2025 outage](https://blog.cloudflare.com/18-november-2025-outage/).
- Each instance reports its current `revision` in `/internal/status`, so tests and operators can confirm convergence.

**Lifecycle (no interruption):**

| Event | Behavior |
|---|---|
| **Startup / scale-up** | The web server starts immediately (readiness DOWN, public listener 503 `gateway.not_ready`). A background bootstrap then runs Flyway migrations with the separate migration credential (Flyway takes a DB lock, so concurrent starts are safe). Load the full snapshot, generate this instance's key and register its public key in `gateway_keys`, start `LISTEN`, and open the Valkey connection (up to 3 attempts; if Valkey stays unreachable, start in degraded rate-limit mode). Only then report **ready** (Spring Boot readiness probe). The load balancer sends no traffic until then. |
| **DB down at startup** | Retry with backoff and stay **not ready**. Never serve with an empty or partial route table. |
| **DB down while running** | Keep serving the last-known-good snapshot. Admin writes return 503. Readiness stays up (traffic is fine), and a health detail reports `configStore: DOWN`. On recovery, poll and converge. The exception: after `key-stale-after − token-ttl − skew` (default 8.5 min) without a heartbeat, the instance reports not-ready (see [identity.md](identity.md#jwks-endpoint-in-scope)). |
| **Graceful shutdown / scale-down** (SIGTERM) | Readiness goes down. The instance keeps accepting requests for a `shutdownDelay` of at least LB probe interval × failure threshold, so the LB has time to stop routing to it (the Kubernetes preStop-sleep pattern). Then in-flight requests finish within a drain timeout (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase`). Mark this instance's key `retired_at = now`; it stays in the JWKS until the identity-token TTL plus skew has passed. Close the DB connections. |
| **Crash (no graceful shutdown)** | Other instances are unaffected. The dead instance's key stops heartbeating and is dropped from the JWKS once `last_heartbeat` is older than TTL + skew + heartbeat interval. |
| **Rolling restart / deploy** | The combination of the above. The LB plus readiness plus graceful drain means clients see zero errors. |

**Seed data:**
- The YAML seed applies only when the DB is empty (`config_revision = 0`), via an explicit `gateway seed` command or a `seedIfEmpty` flag.
- It **never** overwrites runtime changes on restart.

**Security of the store:**
- The DB credentials come from env/secret, never the repo.
- Connections use TLS (`sslmode=verify-full` against DigitalOcean's CA cert; on App Platform the JDBC driver's `SingleCertValidatingFactory` reads the CA from an env var, see [deployment.md](deployment.md)).
- Two DB users:
  - The migration user (DDL) is used only by Flyway. Migration refuses to start until the app role exists, because the V2 grants run only once (R22).
  - The app user has DML on the gateway tables, and only `INSERT`/`SELECT` on `config_audit`.
- All SQL is parameterized.
- Upstream URLs are **re-validated when loaded** from the DB, not just on write, so a tampered row can't point a route at an attacker-chosen host without being rejected.

**Rate limiting with multiple instances:** quotas are shared through Valkey (§2), so scaling the gateway doesn't change any tenant's effective quota.

**Resilience tests:** R1–R21 and the `compose-smoke` job in [testing.md](testing.md#multi-instance-resilience-r1r21).

## 2. Shared rate limiting (Valkey)

**Why Valkey:** it's the open-source (BSD) fork of Redis and speaks the same protocol. DigitalOcean's managed offering is Valkey, and Java clients (Lettuce, via Spring Data Redis) work unchanged. Either would be equally easy to build; Valkey matches the hosting.

**Algorithm:** one atomic Lua script per request (`src/main/resources/ratelimit/sliding_window.lua`), run with `EVALSHA` on key `rl:<tid>`. It's an exact sliding-window log, not a token bucket; [design.md §6](design.md#6-policy-enforcement) explains why.
- The script keeps one sorted-set entry per admission (score = time in ms, member = request ID).
- It removes entries older than 60 s, admits if fewer than RPM remain, and otherwise returns `retry_after_ms` = oldest + 60 s − now.
- Time comes from the Valkey server's `TIME` command inside the script, so gateway clock skew can't affect quotas. Tests can pass a `now_ms` override.
- `PEXPIRE` is set to the window (60 s) on each admission, so idle tenants' keys expire. At most RPM entries exist per tenant.
- No client input goes into the script except the authenticated `tid` and configured numbers.

**Latency:** one round trip per allowed request, over a pooled, pipelined Lettuce connection. A tight command timeout (e.g. 50ms) keeps a slow Valkey from slowing traffic.

**Failure mode: degrade, don't fail.**
- If Valkey times out (50 ms command timeout, 200 ms connect timeout) or errors, the limiter falls back to `LocalRateLimiter` with **RPM ÷ live instance count**. The live count comes from `gateway_keys` heartbeats (§1).
- Quotas then stay approximately right, traffic keeps flowing, and the gateway logs a warning and sets a `ratelimit.degraded` metric.
- A circuit breaker probes Valkey and switches back to shared counting on recovery.
- The connection is opened during startup, before readiness. Opening it on the first request instead would let a cold connect (DNS, TLS, client init) exceed the 200 ms connect timeout on a new instance. That instance would then count locally while its peers counted in Valkey, briefly admitting more than RPM on every deploy and scale-up. This was seen on App Platform: 67 of 70 requests admitted at RPM 60 (R23).
- The alternatives were rejected: fail-closed would turn a cache outage into a full outage, and fail-open would remove quotas entirely.

**Security:**
- TLS (`rediss://`).
- Where ACLs are supported, a dedicated ACL user limited to `rl:*` keys, `EVALSHA`/`EVAL`/`SCRIPT LOAD`, the commands the script calls (`ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`, `ZRANGE`, `PEXPIRE`, `TIME`), and `@connection` (see `docker-compose.yml`).
- DigitalOcean Managed Valkey doesn't support ACL users, so there the gateway uses the `default` user on a cluster dedicated to it, restricted by trusted sources.
- Credentials come from env/secret only.

**Accepted gaps:**
- If Valkey restarts and loses data, buckets refill to full, so each tenant can briefly burst up to one bucket.
- Degraded mode is approximate while the instance count is changing.

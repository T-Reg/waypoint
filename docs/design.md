# Design: Multi-Tenant API Gateway

Internal working design doc (overview). Deep dives: [identity.md](identity.md) (tokens, JWKS), [multi-instance.md](multi-instance.md) (shared state, lifecycle, rate limiting), [privacy.md](privacy.md). Tests: [testing.md](testing.md). Also: [README](../README.md), threat model [security_posture.md](../security_posture.md), [research.md](research.md).

Status: **implemented.** Where the build refined the original design, the change is noted inline and in the decisions log (§11).

## 0. Glossary

| Term | Meaning |
|---|---|
| **Tenant** | One customer account (e.g. a company). Policy and quota are per tenant. Identified by the `tid` claim. |
| **Caller** | One client app or service belonging to a tenant (web app, batch job). Each gets its own token. Identified by `sub`. |
| **Route** | A shared, global mapping from a path prefix to upstream(s), e.g. `/weather` → weather service. Not owned by any tenant. |
| **Allowlist** | The path prefixes a tenant may call. It selects which of the shared routes the tenant can use. |
| **Scope** | A permission carried in a specific token (e.g. `weather:read`). A route can require scopes per HTTP method (e.g. `GET` → `weather:read`, `POST` → `weather:write`). |
| **Upstream** | The backend service the gateway forwards to. |
| **Customer token** | The JWT a caller sends in `Authorization: Bearer …`. Signed by the token issuer and verified by the gateway. It never leaves the gateway. See §4. |
| **Identity token** | The short-lived JWT the gateway mints per request and sends to the upstream as `X-Gateway-Identity`. Signed by the gateway and verified by upstreams. See §7a. |
| **Token issuer** | Whatever signs customer tokens. Today it's a stand-in CLI; in production it would be an identity provider / token service. See §4. |

**Example:**

| Tenant | Allowlist | Quota | Tokens (scopes) |
|---|---|---|---|
| Acme | `/weather`, `/maps` | 600/min | web app (`weather:read maps:read`), batch job (`weather:read`) |
| Globex | `/weather` | 60/min | one token (`weather:read`) |

- Globex calling `/maps` gets **403 `policy.path_denied`**: `/maps` isn't on Globex's allowlist. That's the answer whether or not the route exists.
- Acme's batch job calling `/maps` gets **403 `policy.scope_missing`**: the tenant is allowed, but that token lacks `maps:read`.

## 1. Goals and non-goals

**Goals (from `CodeBash.md`):**
- Authenticate callers and resolve a tenant for every request.
- Route by path prefix to configurable upstreams, editable at runtime.
- Enforce per-tenant policy before proxying: path allowlist, per-minute quota, required scopes.
- Write a structured audit entry for every request.

**Non-goals (for now):**
- Circuit breaking, canary routing and deployment. The design leaves seams for these (§10).
- TLS termination. It's assumed to happen in front of the gateway (load balancer or ingress).

## 2. Stack

| Concern | Choice | Why |
|---|---|---|
| Language/runtime | Java 21 | My strongest language; has virtual threads. |
| Framework | Spring Boot 4.1, Spring MVC on virtual threads | Simple blocking code that still scales to many concurrent connections, with no reactive complexity. |
| Upstream HTTP | JDK `java.net.http.HttpClient` (one shared instance) | Supports timeouts and streaming, has no extra dependency, and can have redirects turned off. |
| JWT | Nimbus JOSE+JWT 10.10 + Google Tink, fully-specified `alg: Ed25519` ([RFC 9864](https://www.rfc-editor.org/rfc/rfc9864.html)) | A mature library that supports algorithm pinning. Nimbus delegates Ed25519 to Tink. Verified: 10.10 supports `JWSAlgorithm.Ed25519`, so no `EdDSA` or ES256 fallback is needed. |
| Build | Maven wrapper | Reproducible builds; CI-friendly. |
| Shared rate limit | Valkey 8 (DigitalOcean Managed Valkey in prod), Lettuce via Spring Data Redis | Atomic Lua sliding-window quota shared by all instances (§6); Redis-protocol compatible. |
| Config store | PostgreSQL 16 (DigitalOcean Managed PostgreSQL in prod), JDBC + HikariCP, Flyway migrations | Shared, durable config. `LISTEN/NOTIFY` gives change push without an extra broker. |
| Tests | JUnit 6, jqwik, WireMock, Spring Boot test (random port), Testcontainers (PostgreSQL, Valkey, Toxiproxy), Awaitility, JaCoCo | Unit, property, integration, multi-instance resilience and coverage tests. |

**Scaffolding:** The Maven wrapper came from Spring Initializr, and the `pom.xml` was written by hand so that every dependency is deliberate.

**Boot 4.1:** The project runs on the latest Boot GA line, with no overrides of Boot-managed dependency versions. JSON uses Jackson 3 (`tools.jackson`), whose mappers are immutable, so one shared mapper is safe to inject everywhere. Annotations remain in `com.fasterxml.jackson.annotation`.

**We do not use Spring Cloud Gateway.** It would provide routing, filters and rate limiting for us, and the exercise is to show our own design. We use it only as a reference.

## 3. Request pipeline

This is a filter chain, the same pattern as Zuul inbound filters, Envoy HTTP filters and Pingora phases.

```
RequestId → PathNormalize → Authenticate → Allowlist → RouteMatch → Method → Scope → Quota → Proxy
                                                                                    └─ finally: Audit
```

- Each stage returns `Continue(context)` or `Reject(status, code, message)`. A `Reject` short-circuits the chain, so a policy violation **never reaches the upstream**. In code, a stage returns `Optional<Rejection>`, where empty means continue (`pipeline/Stages.java`).
- Until the instance has loaded its first snapshot, the public listener answers **503 `gateway.not_ready`**. It never serves from an empty table.
- A body-size check runs before normalization (413). Chunked bodies are also capped while they stream.
- The audit entry is emitted in a `finally` block, so every request is logged: allowed, rejected, failed upstream, or unexpected exception.

**Ordering rationale:**
- **Authenticate, then check the allowlist, before routing.** Otherwise callers could tell existing routes (403) from missing ones (404) and map the route table. A tenant can only get a 404 for paths on its own allowlist.
- **Quota check last.** Requests that would be denied anyway don't use up quota.
- **Normalize first.** Matching and policy must both see the *same* canonical path.

Diagram: see [architecture.md](architecture.md).

**Listeners:**

| Listener | Serves | Auth | Reachable from |
|---|---|---|---|
| Public | Proxy only | Customer JWT | Internet, via the LB |
| Admin | `/admin/*` | Admin JWT (§5) | Operator network only |
| Management | `/.well-known/jwks.json`, `/internal/status`, Actuator health/readiness (nothing else exposed) | None (no secrets served) | LB health checks and upstreams |

The proxy never forwards to its own listeners (§5, upstream URL validation).

## 4. Authentication

Callers send a **customer token**: an Ed25519-signed JWT (`typ=customer+jwt`, `aud=gateway`) from the token issuer, verified with the issuer's public key. The tenant comes only from the verified `tid` claim. Admin tokens use a separate key (§5), and identity tokens are minted by the gateway (§7a). Claims, validation rules, the issuer stand-in and revocation are in [identity.md §1](identity.md#1-authentication-customer-and-admin-tokens).

## 5. Dynamic routing

- **Model:**

  | Field | Meaning |
  |---|---|
  | `id` | Route ID |
  | `pathPrefix` | Path prefix the route matches |
  | `upstreams[]` | `(url, weight)` pairs |
  | `stripPrefix` | Whether to remove the prefix before forwarding |
  | `requiredScopes` | Map of HTTP method → scopes the token must contain, e.g. `{GET: [weather:read], POST: [weather:write]}`. A `*` entry is the default for unlisted methods. An empty list explicitly allows any authenticated, allowlisted tenant. A method with no entry and no `*` gets **405** `policy.method_denied` with an `Allow` header ([RFC 9110 §15.5.6](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.6)). |
  | `forwardScopes[]` | Scope patterns copied into the identity token, e.g. `weather:*`. Defaults to the union of namespaces (the prefix before `:`) across the route's required scopes. If there are none, no scopes are forwarded. See §7a |
  | `forwardIdentity` | Whether to send `X-Gateway-Identity` (default `true`). Set it to `false` for third-party upstreams, so tenant identifiers aren't leaked to them |
  | `audience` | `aud` for the identity token sent to this route's upstream (defaults to `id`); see §7a |
  | `timeoutMs` | Upstream timeout: the maximum wait for response headers, and for each read of the response body (an idle timeout, so long downloads still stream) |

  `upstreams` is a list from day one so canary routing needs no schema change.
- **Storage:** an immutable `RouteTable` snapshot behind an `AtomicReference`. An admin write validates the change, builds a new table, then swaps it in atomically. In-flight requests keep the snapshot they started with.
  - Envoy RDS works the same way: route config is "gracefully swapped in without affecting existing requests", and a bad update leaves the last-known-good config in place.
- **Matching:** longest prefix match on **path segments**, so `/api` matches `/api` and `/api/x` but not `/apix`. We start with a list sorted by prefix length. A segment trie is an optimization note for large route counts.
- **Path normalization** (one canonical path, used for the allowlist, matching **and** forwarding). Ambiguous paths get 400 `request.bad_path`.
  - Percent-decode exactly once. Reject anything still containing `%` (double encoding), invalid encodings, NUL bytes, `\` (raw or encoded) and encoded `/`.
  - Reject `;` anywhere in the path. Java servers such as Tomcat strip `;…` path parameters, so `/..;/` acts as `/../` ([Orange Tsai, Black Hat 2018](https://i.blackhat.com/us-18/Wed-August-8/us-18-Orange-Tsai-Breaking-Parser-Logic-Take-Your-Path-Normalization-Off-And-Pop-0days-Out-2.pdf)).
  - Reject `..` segments, remove `.` segments, collapse repeated `/`, and drop a trailing `/`. Matching is case-sensitive.
  - Forward the canonical path **re-encoded** per [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986#section-2.1), never the raw request URI.
  - The query string is kept apart and forwarded unchanged.
- **Admin API:**
  - Endpoints: `GET /admin/routes`, `GET/PUT/DELETE /admin/routes/{id}`, `GET /admin/tenants`, `GET/PUT/DELETE /admin/tenants/{id}` and `POST /admin/keys/rotate` (§7a).
  - `PUT` creates (201) or updates (200). Responses carry `ETag: "<version>"`. Updating an existing entry **requires** `If-Match`: if it's missing the answer is 428 `admin.precondition_required`, and if it's stale, 409. `DELETE` accepts an optional `If-Match`. Unknown JSON fields are rejected (400), so a typo such as `allowPrivateNetwrok` can't be silently ignored.
  - It runs on the **admin listener** (§3) and requires an **admin JWT**, so tenant tokens can never reach it.
  - **Admin JWT:**
    - Signed with a **separate admin key**, with its own `iss`, `aud=gateway-admin` and `typ=admin+jwt`.
    - Each listener trusts only its own key. Customer tokens are rejected on the admin listener, admin tokens on the public one, and stealing the customer-issuer key doesn't grant admin.
    - Scopes: `admin:routes`, `admin:tenants`, `admin:keys`.
    - Short TTL (e.g. 10 min).
    - Minted with `token-cli issue-admin --sub alice --scope "admin:routes"`.
    - Its `sub` is recorded as `updated_by` and in `config_audit`, so every change has a named actor.
  - Admin errors: 401 for a missing/invalid/wrong-`aud` token; 403 `admin.scope_missing` for an insufficient scope; 400 `admin.invalid`; 404 `admin.not_found`; 409 `admin.conflict` on a version or prefix conflict; 428 `admin.precondition_required`; 503 `admin.store_unavailable` if the DB is down (§5a).
  - At startup the gateway refuses to run if the admin and customer trust roots share a key, issuer or audience.
  - Every admin request, including denied ones, is written to the audit log.
  - **Upstream URL validation** (SSRF, per the [OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)):
    - Absolute URL, `http`/`https` only, no userinfo.
    - Link-local (including `169.254.169.254` metadata), wildcard, multicast and reserved addresses are **always** denied.
    - Loopback, private (RFC 1918), CGNAT and IPv6 ULA addresses are denied unless the route sets `allowPrivateNetwork: true`.
    - The gateway's own listeners (any of its ports on loopback or a local interface address) are always denied. Another host on the same port is not "self".
    - When: on admin writes, the hostname is resolved and every address checked. On load from the DB, only syntax and IP literals are checked, with no DNS, so a resolver outage can't disable routes. **Before every upstream call**, the host is resolved again and checked, to defeat DNS rebinding.
    - Gap: the JDK `HttpClient` has no connect hook. The per-request check and the connect share the JVM's DNS cache, but a pooled keep-alive connection or a cache expiry between the two leaves a small window. Closing it fully would need a custom `InetAddressResolverProvider` or connecting by IP.
- **Persistence:** routes and tenants live in a shared PostgreSQL database. Each instance keeps an in-memory snapshot for the request path. See §5a.

## 5a. Multi-instance operation

PostgreSQL is the source of truth for routes and tenants. Each instance serves from an in-memory snapshot, so the request path never touches the DB. Admin writes reach every instance via `NOTIFY` (with a poll fallback). Readiness, graceful shutdown and per-entry validation make reboots and scaling lossless and error-free. Details are in [multi-instance.md](multi-instance.md).

## 6. Policy enforcement

- **Tenant policy:** `allowedPathPrefixes[]` (segment-aware, like routing), `requestsPerMinute`, `disabled` (default `false`, see §4) and `auditRawPath` (default `false`, see §8).
- **Route policy:** `requiredScopes` for the request's HTTP method (falling back to `*`). The token's scopes must contain all of them. Per-tenant variation comes from the allowlist, not from scopes.
- **Quota:** an exact **sliding-window log** per tenant: at most RPM admissions in any 60 s window.
  - *Changed from the token bucket in the first draft.* A bucket with capacity = RPM and refill = RPM/60 s admits up to 2×RPM in a 60 s window, which contradicts the required invariant ("never more than RPM in any 60 s window") and the exact-count scenarios R16/R17.
  - The sliding log still allows bursts up to RPM and gives an exact `Retry-After` (when the oldest admission leaves the window).
  - Memory is bounded: at most RPM entries per tenant (RPM ≤ 100 000), and the key expires after one window.
  - **It is shared across all instances via Valkey**, so N instances still enforce exactly RPM per tenant. See [multi-instance.md §2](multi-instance.md#2-shared-rate-limiting-valkey).
  - It sits behind a `RateLimiter` interface with two implementations: `ValkeyRateLimiter` (production) and `LocalRateLimiter` (in-memory; used for unit tests and as the degraded fallback).
  - Buckets are keyed only by **authenticated** tenant IDs, so the key space stays bounded by the number of tenants.
  - An injected `Clock` keeps tests deterministic.
- **Errors:** RFC 9457 `application/problem+json` with a machine-readable `code` and the `request_id`.

| Case | Status | `code` |
|---|---|---|
| Missing/malformed/invalid/expired token | 401 + `WWW-Authenticate: Bearer` | `auth.missing`, `auth.invalid`, `auth.expired` |
| Unknown or disabled tenant | 401 | `auth.unknown_tenant` |
| Path not in tenant allowlist | 403 | `policy.path_denied` |
| HTTP method not configured on the route | 405 + `Allow` | `policy.method_denied` |
| Missing required scope | 403 | `policy.scope_missing` |
| No matching route | 404 | `route.not_found` |
| Malformed/ambiguous path (or a query the upstream URI can't carry) | 400 | `request.bad_path` |
| Body too large | 413 | `request.too_large` |
| Quota exceeded | 429 + `Retry-After` | `policy.quota_exceeded` |
| Upstream connection failure | 502 | `upstream.unavailable` |
| Upstream timeout (headers, or body idle before anything is sent to the client) | 504 | `upstream.timeout` |
| Upstream body fails after the response is committed | connection closed, no terminating chunk | `upstream.timeout` / `upstream.stream_interrupted` (audit only) |
| Route's config entry invalid (§5a) | 503 | `route.unavailable` |
| Instance still starting (no snapshot yet) | 503 | `gateway.not_ready` |
| Unexpected error | 500 (no stack trace) | `internal` |

## 7. Proxying

- Remove hop-by-hop headers in both directions: `Connection`, any header named in `Connection`, `Keep-Alive`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade` and `Proxy-*` ([RFC 9110 §7.6.1](https://www.rfc-editor.org/rfc/rfc9110#section-7.6.1)).
- **Strip the inbound `Authorization` header, any client-sent `X-Gateway-*` headers, and `Forwarded`, `X-Real-IP` and `X-Forwarded-*`.** Then inject `X-Gateway-Identity` (a signed identity token, see §7a) and `X-Request-Id`.
  - Stripping happens before injection, so `Connection: X-Gateway-Identity` can't remove the gateway's own headers ([hop-by-hop abuse](https://nathandavison.com/blog/abusing-http-hop-by-hop-request-headers)).
  - Spoofed forwarding headers caused [Spring Cloud Gateway CVE-2025-41235](https://advisories.gitlab.com/pkg/maven/org.springframework.cloud/spring-cloud-gateway-server/CVE-2025-41235/).
- Rebuild `X-Forwarded-For` from the computed client IP chain, and set `X-Forwarded-Proto` and `-Host`.
- `Host` is the upstream's host. The JDK client sets it, and also rejects `Content-Length`, `Connection`, `Expect` and `Upgrade` as restricted headers ([java.net.http](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)). Those headers are never copied, and the client frames the body itself.
- **Trusted proxies:** the gateway runs behind a TLS terminator / load balancer that we don't build.
  - A `trustedProxies` CIDR list is set in config.
  - The client IP is taken from `X-Forwarded-For` **only** when the direct peer is in that list. It is the right-most address that isn't a trusted proxy.
  - Otherwise the client IP is the socket peer address. This stops clients from spoofing their IP in the audit log.
- Stream the request and response bodies, with a configurable maximum request size (`gateway.proxy.max-request-bytes`, default 10 MiB). The JDK client is fixed to HTTP/1.1 and never follows redirects.
- Requests Tomcat itself rejects before any filter runs (e.g. raw illegal characters in the request line) get Tomcat's 400 and aren't audited. Encoded `/` is passed through to the normalizer so it gets an audited 400.
- Return the upstream status, headers and body faithfully.
- Every public response carries `X-Request-Id`. With `gateway.proxy.expose-instance-header` (off by default) it also carries `X-Gateway-Instance` with the serving instance's ID, so a demo client can tell instances apart behind a load balancer that doesn't say. Both are set last, so an upstream can't override them.
- Bodies are forwarded byte-for-byte. The gateway never parses them and doesn't restrict content types.

## 7a. Identity propagation to upstreams

After policy passes, the gateway sends a short-lived `X-Gateway-Identity` JWT. It's signed with the gateway's own key, bound to one upstream (`aud`), and carries `tid`, `sub` and filtered scopes. Upstreams verify it via the JWKS on the management listener and do object-level authorization. This addresses the confused-deputy problem. Details are in [identity.md §2](identity.md#2-identity-propagation-to-upstreams).

## 8. Audit log

- One JSON line per request on a dedicated `audit` logger, written to stdout. Entries are serialized with Jackson, which escapes control characters, so log injection can't forge lines.
- **Fields:**

  | Field | Notes |
  |---|---|
  | `ts`, `request_id` | |
  | `tenant_id` | Null if unauthenticated |
  | `method` | |
  | `path_prefix` | The matched route prefix (low sensitivity), e.g. `/cities` |
  | `path_hmac` | Keyed HMAC-SHA256 of the full normalized path. Identical requests can be correlated without storing the path. The key comes from a secret, can be rotated, and is **not** given to log readers. Anyone holding it can brute-force low-variety paths (e.g. city names), so this is pseudonymization, not anonymization. |
  | `path` | Raw normalized path, **only** if the tenant has `auditRawPath: true` |
  | `route_id`, `upstream` | |
  | `decision` | `ALLOW` or `DENY` |
  | `deny_code` | |
  | `upstream_status` | |
  | `status` | Status returned to the client |
  | `error_code` | Non-policy failure after `ALLOW`, e.g. `upstream.timeout` |
  | `sub` | Caller (`sub`) for public requests, admin `sub` for admin requests |
  | `listener` | `public` or `admin` |
  | `latency_ms` | |
  | `client_ip` | |

- **Path minimized by default** (decision): the brief's "path" field is met by `path_prefix` + `path_hmac`, and raw paths are opt-in per tenant.
  - Trade-off: debugging needs the HMAC (recompute it for a suspected path) or a temporary opt-in.
- **Never logged:** tokens, query strings and bodies.
- Admin-listener requests are audited too, including auth failures.
- **Interface:** `AuditSink`: stdout now. Shipping to an append-only store (tamper resistance) is a known gap.

## 9. Confidentiality & traffic privacy

TLS hides paths from the network, but the gateway sees everything. It therefore minimizes what it keeps (§8), forwards bodies untouched so tenants can encrypt end to end, and defers gateway-blind modes. Details are in [privacy.md](privacy.md).

## 10. Extension seams (not built)

| Extension | Seam |
|---|---|
| Circuit breaker | `UpstreamClient` decorator per upstream URL: closed → open after N failures in a window → half-open probe after cooldown. Config on the route. |
| Canary routing | `UpstreamSelector` over `route.upstreams[]`: weighted random, or sticky per tenant via `hash(tid)`. |
| Deployment | Dockerfile, an App Platform spec (`.do/app.yaml`) and a [DigitalOcean guide](deployment.md). Infrastructure is provisioned manually. |

## 11. Decisions log

| Question | Decision | Why |
|---|---|---|
| Where do required scopes live? | Per route **and HTTP method** (§5); unconfigured methods get 405 | Read/write separation; per-tenant control already comes from the allowlist |
| Which scopes go in the identity token? | Filtered by per-route `forwardScopes` (§7a) | Least privilege but flexible. Upstream-declared scope needs are a future improvement |
| Admin API credential? | Admin JWT with its own signing key, `aud=gateway-admin`, `admin:*` scopes (§5) | Named actor in `config_audit`, short-lived, reuses the verifier; stealing the customer-issuer key doesn't grant admin |
| Scaffolding? | Spring Initializr `curl` + line-by-line `pom.xml` review (§2) | Fast and reproducible, and every dependency is still deliberate |
| Audit path logging? | Minimized by default (prefix + HMAC); raw path opt-in per tenant (§8) | Private by default; correlation still possible |
| Quota algorithm? | Exact sliding-window log in Valkey, not a token bucket (§6) | The bucket admits up to 2×RPM per window, contradicting the required invariant and R16/R17 |
| JWT algorithm? | `alg: Ed25519` only (RFC 9864); `EdDSA` rejected | Nimbus 10.10 supports the fully-specified identifier, so no fallback is needed |
| Updating without `If-Match`? | 428 Precondition Required ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585#section-3)) | Forces optimistic locking on every update, so lost updates can't happen by omission |
| Spring Boot line? | 4.1 with Jackson 3 (§2) | Boot 3.5 left open-source support in June 2026 and needed patched-dependency overrides plus CVE ignores |
| Which instance served a request, behind App Platform? | Opt-in `X-Gateway-Instance` response header (§7), enabled only for the demo deployment | The platform load balancer adds no such header and instances aren't individually addressable; a random instance ID leaks little, and it stays off by default |

## 12. Open questions
None currently.

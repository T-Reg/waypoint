# Security Posture & Threat Model

The structure follows the [Four Question Framework](https://shostack.org/files/papers/The_Four_Question_Framework.pdf).

## 1. What are we working on?

A reverse proxy that authenticates tenants with Ed25519-signed JWTs, routes by path prefix to upstreams, enforces per-tenant policy, and audits every request. See [docs/architecture.md](docs/architecture.md).

**Assets:**
- The customer-token signing private key (held by the issuer CLI, never by the gateway).
- The gateway's identity-token signing private key (held by the gateway; used to sign `X-Gateway-Identity`).
- Tenant identity and scopes.
- Tenant quotas, which affect fairness and availability.
- The route table and admin API.
- The PostgreSQL config store and its credentials. Whoever can write it controls routing.
- The `config_audit` trail of admin changes.
- Upstream services.
- The audit log, which is integrity- and privacy-sensitive.

**Trust boundaries:**
1. Internet → TLS terminator → gateway.
2. Gateway → upstream services.
3. Admin client → admin listener (admin JWT signed with its own key). Upstreams and LB → management listener (JWKS, health; no secrets).
4. Token issuer (CLI with the private key) → gateway (public key only).
5. Gateway (identity-token key) → upstreams (verify with the gateway's JWKS).
6. Gateway instances → PostgreSQL (TLS, least-privilege DB user).
7. Gateway instances → Valkey (TLS, ACL user limited to rate-limit keys where the host supports ACLs; DigitalOcean Managed Valkey doesn't, see §5).

## 2. What can go wrong? (STRIDE)

| Element | Threat | Category | Mitigation | Status |
|---|---|---|---|---|
| Public listener | Unauthenticated flood (no tenant, so no quota) fills CPU and audit volume | DoS | Cheap rejection before any DB or Valkey call; network-level rate limiting/DDoS protection belongs to the LB/edge (known gap) | Partial |
| Auth | Forged token / `alg: none` / algorithm confusion / token-type confusion | Spoofing | Algorithm pinned to `Ed25519` (`EdDSA`, HS*, `none` rejected); explicit `typ` per token type; single-valued `aud`; `jku`/`x5u`/`jwk` headers ignored ([RFC 8725](https://datatracker.ietf.org/doc/html/rfc8725)) | Implemented |
| Auth | Replay of a stolen token | Spoofing | Short TTL; `jti` denylist (future) | Partial |
| Auth | Token for another audience/issuer accepted | Spoofing | `iss` and `aud` required and checked | Implemented |
| Issuer | Issuer private key stolen, so an attacker can mint a customer token for any tenant | Spoofing / Elevation | Key file kept outside the repo, and the gateway never holds it. Admin tokens use a separate key, so this doesn't grant admin. Planned: KMS/HSM-held key, `kid` rotation via JWKS | Partial |
| Routing/policy | Path encoding tricks (`%2F`, `..`, `..;`, `;`, double encoding, `//`, `\`) to bypass the allowlist | Elevation | One canonical path for allowlist, matching and forwarding (re-encoded); ambiguous paths rejected with 400; property tests | Implemented |
| Routing | Authenticated tenant maps routes outside its allowlist (404 vs 403) | Info disclosure | Allowlist checked before route lookup | Implemented |
| Routing | Prefix confusion (`/api` matching `/apix`) | Elevation | Segment-aware matching | Implemented |
| Policy | Write operation allowed with a read-only token | Elevation | Required scopes per HTTP method; methods without an entry get 405 | Implemented |
| Rate limiter | One tenant exhausts shared capacity | DoS | Per-tenant token bucket; body-size limit; upstream timeouts on both response headers and body idle time (a stalled body is closed after `timeoutMs` of silence) | Implemented |
| Rate limiter | Scaling out multiplies a tenant's quota | Elevation / DoS | Shared Valkey sliding window enforces one quota across all instances (tested, R16–R17); each instance connects to Valkey before it reports ready, so a new instance doesn't count locally on a cold connect (R23) | Implemented |
| Rate limiter | Valkey outage or slowness becomes a gateway outage | DoS | 50ms command timeout; degrade to local bucket at RPM ÷ live instances; circuit breaker re-probes (tested, R18–R19) | Implemented |
| Rate limiter | Valkey credentials leaked, or another client resets buckets | Tampering | TLS, credentials from env/secret, trusted sources. ACL user limited to `rl:*` and script commands where supported (Compose); DigitalOcean Managed Valkey allows only the `default` user, so it uses a cluster dedicated to the gateway | Partial |
| Rate limiter | Unbounded bucket map growth | DoS | Buckets keyed only by authenticated, known tenants | Implemented |
| Proxy | Client spoofs `X-Gateway-*` identity headers to an upstream | Spoofing | Strip client `X-Gateway-*` headers; identity is sent only as a gateway-signed token | Implemented |
| Proxy | `Connection: X-Gateway-Identity` strips the gateway's identity header | Spoofing | Hop-by-hop stripping happens before injection (tested) | Implemented |
| Proxy | Identity token sent to a third-party upstream leaks tenant identifiers | Info disclosure | Per-route `forwardIdentity: false` | Implemented |
| Proxy | Tenant token leaked to the upstream | Info disclosure | Strip `Authorization` before forwarding | Implemented |
| Proxy | Request smuggling via hop-by-hop / `Transfer-Encoding` | Tampering | Remove hop-by-hop headers; the JDK client re-frames the body | Implemented |
| Proxy | Upstream sends headers then stalls the body, pinning gateway connections; or a body cut off mid-stream reaches the client as if complete | DoS / Tampering | Body idle timeout (`timeoutMs` per read, 504 if nothing was sent yet); a failure after commit closes the client connection with no terminating chunk (tested) | Implemented |
| Proxy | Identity token over-shares the caller's unrelated scopes with an upstream | Info disclosure | `scope` filtered by per-route `forwardScopes` (tested) | Implemented |
| Proxy / Audit | Client spoofs `X-Forwarded-For`, `Forwarded` or `X-Real-IP` to fake its IP | Spoofing / Repudiation | XFF is trusted only from `trustedProxies`; inbound forwarding headers are stripped and rebuilt | Implemented |
| Upstream | Confused deputy: an internal caller claims a tenant identity, or an upstream acts for a tenant it shouldn't | Spoofing / Elevation | Signed `X-Gateway-Identity` with per-upstream `aud` and 60s TTL; upstreams verify it and do object-level authz. Second hop: token exchange (RFC 8693) + mTLS, deferred | Partial |
| Upstream | Identity token replayed to a different service | Spoofing | `aud` bound to one upstream; short `exp` | Implemented |
| Gateway | Gateway identity key leaks, letting an attacker mint internal identities | Spoofing | Key generated in memory at startup and never written to disk; `kid` rotation via JWKS; 60s TTL limits the value of individual tokens | Partial |
| JWKS | Private key material published by mistake | Info disclosure | Only public params serialized; unit test asserts no `d` | Implemented |
| JWKS | JWKS unavailable during a DB outage or partition | DoS | Served from an in-memory last-known-good key set; keys not expired while the DB is unreachable (tested, R6, R20) | Implemented |
| JWKS | JWKS response spoofed, so an upstream trusts an attacker's key | Spoofing | Served on the management listener only; upstreams fetch from a fixed configured URL over TLS and ignore `jku`/`x5u`/`jwk` token headers (tested) | Implemented |
| JWKS | Flood of unknown `kid`s forces upstreams to refetch repeatedly | DoS | Verifier guidance: cache keys and rate-limit refetches; `Cache-Control` on the response | Implemented |
| Audit | Log injection / tokens in logs | Tampering / Info disclosure | Structured JSON encoder; tokens, bodies and query strings never logged | Implemented |
| Audit | Logs reveal end-user behavior (e.g. location in the path) | Info disclosure | Path minimized **by default** (matched prefix + keyed HMAC); raw path only on per-tenant opt-in | Implemented |
| Audit | Tenant denies having made requests | Repudiation | Request ID and tenant ID on every entry | Implemented |
| Audit | Audit log altered or deleted | Tampering | Structured stdout; on App Platform, forwarded to Managed OpenSearch outside the gateway's control. Not tamper-evident: `doadmin` can delete indexes, and forwarding is best-effort | Partial |
| Audit | Log cluster reachable from the internet (forwarding requires trusted sources off) | Info disclosure / Tampering | TLS and `doadmin` password, rotated after creation; a third-party destination avoids it | Partial |
| Audit | Requests Tomcat rejects before the filter chain (illegal request-line characters) leave no audit entry | Repudiation | Encoded `/` passed through to the normalizer for an audited 400; other parser-level rejections still unaudited | Gap |
| Admin API | Unauthorized key rotation | Tampering | `POST /admin/keys/rotate` requires an admin JWT with `admin:keys` and isn't reachable on the public listener (tested) | Implemented |
| Admin API | Tenant reaches the admin API | Elevation | Separate admin listener; admin JWT with its own key, `iss` and `aud=gateway-admin`, so customer tokens are rejected there and vice versa (tested) | Implemented |
| Admin API | Brute-forcing or probing the admin API goes unnoticed | Repudiation | Every admin request, including auth failures, is audited | Implemented |
| Admin API | Stolen admin token | Elevation | Short TTL (~10 min); least-privilege `admin:routes` / `admin:tenants` / `admin:keys` scopes; every change attributed to `sub` in `config_audit` | Partial |
| Admin API | SSRF via an upstream URL (metadata `169.254.169.254`, loopback, the gateway's own listeners) | Elevation | Scheme/absolute/no-userinfo/no-query validation; link-local, wildcard and multicast always denied; private and loopback denied unless a route opts in; own listeners always denied; hostname re-resolved and re-checked before every upstream call ([OWASP SSRF](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)). No true connect-time hook (see §5) | Partial |
| Admin API | Admin and customer trust roots accidentally share a key | Elevation | Startup fails if the admin and customer key sets share a key, issuer or audience | Implemented |
| Admin API | Typo in a security flag silently ignored (e.g. `allowPrivateNetwrok`) | Tampering | Unknown JSON fields rejected with 400 | Implemented |
| Config store | Tampered DB row points a route at an attacker host | Tampering / Elevation | Least-privilege DB user, TLS (`verify-full`), upstream URLs re-validated on load, invalid entries skipped (fail closed per route) | Implemented |
| Config store | One bad row stops instances from starting | DoS | Per-entry validation at load and startup (tested, R13) | Implemented |
| Config store | DB credentials leaked | Elevation | Credentials from env/secret only; DDL only for the migration user; app user `INSERT`/`SELECT` only on `config_audit`; DigitalOcean trusted sources restrict network access | Implemented |
| Config store | App role created after the first migration never gets its grants, so a fresh deploy fails in confusing ways | DoS | Migration refuses to run until the app role exists; the instance stays not-ready and logs why (tested, R22) | Implemented |
| Config store | SQL injection via admin input | Tampering | Parameterized SQL only | Implemented |
| Config store | Two admins overwrite each other's changes | Tampering | Optimistic locking (`If-Match` / `version`), 409 on conflict (tested, R5) | Implemented |
| Config store | Admin denies making a change | Repudiation | Append-only `config_audit` with actor, time, and before/after values | Implemented |
| Config store | DB outage stops traffic | DoS | Request path uses the in-memory snapshot only; DB outage only blocks admin writes (tested, R6) | Implemented |
| Config store | App DB user rewrites the change history | Repudiation | `config_audit` triggers block UPDATE/DELETE/TRUNCATE for every role; the app role gets INSERT/SELECT only (tested) | Implemented |
| Supply chain | Known-vulnerable dependency | Elevation | OSV-Scanner in CI fails the build on any known-vulnerable dependency; Boot 4.1 (supported line) with no version overrides and no ignored advisories | Implemented |

## 3. Malicious authenticated tenant

**Cannot:**
- Reach paths outside its allowlist, or routes that need scopes it doesn't have.
- Use or drain another tenant's quota.
- Forge or alter its tenant ID or scopes, because they're in signed claims.
- Bypass policy with path encoding.
- Impersonate another tenant to upstreams through `X-Gateway-*` headers, or forge the signed identity token.
- Reach the admin API or change routes.
- Read other tenants' audit entries.

**Can:**
- Use its full quota, including bursts up to the bucket capacity.
- Send large or slow requests up to the configured size and timeout limits. Upstream responses are bounded the same way: `timeoutMs` caps the wait for headers and each gap between body chunks.
- Probe routes within its own allowlist.
- Keep using a stolen or leaked token until it expires (no revocation yet).
- Send any payload to its allowed upstreams. The gateway doesn't inspect bodies; that's the upstream's job.

## 4. Privacy

See [docs/privacy.md](docs/privacy.md).
- A network observer sees only the IP, hostname, timing and sizes.
- The gateway sees everything, so we minimize what it keeps.
- Tenants can get gateway-blind privacy by encrypting bodies to their upstream's key; the gateway forwards bytes untouched.

## 5. Known gaps / accepted risks
- The token issuer is a manual CLI with a file-based key, standing in for a real token service. There is no self-service issuance, no revocation, and no HSM/KMS-backed key. See [docs/identity.md §1](docs/identity.md#1-authentication-customer-and-admin-tokens).
- No per-token revocation before `exp`. Disabling or deleting a tenant revokes all its tokens within the propagation bound.
- Audit logs go to stdout. On App Platform they're forwarded to Managed OpenSearch, but that store isn't append-only or tamper-evident, and delivery is best-effort.
- App Platform can't forward to an OpenSearch cluster with trusted sources on, so the log cluster is reachable from the internet behind TLS and a password.
- No network-level rate limiting or DDoS protection for unauthenticated traffic; that's expected from the LB/edge.
- SSRF re-checks resolve the upstream host before each call, but the JDK `HttpClient` has no connect hook. A pooled keep-alive connection or a DNS-cache expiry between check and connect leaves a narrow DNS-rebinding window.
- A total DB outage longer than `key-stale-after − token-ttl − skew` (≈ 8.5 min by default) makes every instance report not-ready, because an instance can't tell a partition from a global outage. Raising `key-stale-after` widens the window.
- Requests rejected by Tomcat's HTTP parser itself aren't audited.
- Rate limiting is shared via Valkey. Accepted risks:
  - During a Valkey outage, quotas are approximate (RPM ÷ live instances).
  - If Valkey loses data, buckets refill to full once.
  - On DigitalOcean Managed Valkey the gateway connects as the `default` user (ACL users aren't supported), so leaked credentials give full access to that cluster. Mitigation: a cluster used only by the gateway, TLS and trusted sources.
- On DigitalOcean App Platform, the edge puts the client IP in `do-connecting-ip` and the ingress IP in `X-Forwarded-For`. The gateway reads only `X-Forwarded-For`, so the audit `client_ip` there is the ingress address. A configurable client-IP header would fix this.
- Config changes are eventually consistent across instances, bounded by the poll interval (default 10s) in the worst case.
- TLS is terminated in front of the gateway; there is no mTLS to upstreams.
- No per-tenant concurrency limit; slow requests can tie up connections up to the timeout.
- No total response deadline, only an idle one: an upstream that trickles a byte just under every `timeoutMs` keeps a connection open. SSE or long-poll routes need a `timeoutMs` above their heartbeat interval.
- If an upstream body fails after the response is committed, the gateway closes the client connection without a terminating chunk, so clients see an error instead of a silently truncated body. The status already sent (e.g. 200) can't be changed.
- No body inspection or WAF.
- Downstream service-to-service calls (second hop) have no token exchange or mTLS yet, so a downstream service must not reuse the identity token for other services.
- Gateway identity private keys live in process memory, not a KMS/HSM.
- Object-level authorization is the upstream's responsibility; the gateway only enforces path- and scope-level policy.
- `forwardScopes` are configured by the gateway operator. Possible future improvement: upstreams declare the scopes they need per request.

## 6. Did we do a good job?

**Mitigation → test** (paths under `src/test/java/dev/waypoint/gateway/`):

| Mitigation | Proven by |
|---|---|
| Alg pinning, `typ`, `iss`/`aud`, exp/nbf, embedded-key headers ignored | `auth/Ed25519JwtVerifierTest`, `auth/CustomerAuthenticatorTest` |
| Tenant only from signed `tid`; unknown/disabled tenant → 401 | `auth/CustomerAuthenticatorTest`, `it/GatewayProxyIT.unknownAndDisabledTenants` |
| Admin and customer tokens can't cross listeners; scopes gate endpoints | `it/AdminApiIT`, `it/IdentityIT.rotateRequiresAdminKeysScopeOnAdminListener`, `it/GatewayProxyIT.adminApiAndJwksAreNotReachableOnThePublicListener` |
| Path-encoding bypasses rejected; one canonical path | `path/PathNormalizerProperties`, `it/GatewayProxyIT.ambiguousPathsAre400` |
| Allowlist before routing (no route mapping) | `pipeline/StagesTest.pathOutsideAllowlistIs403EvenIfRouteExists`, `it/GatewayProxyIT` |
| Rejections never reach the upstream | every rejection test in `it/GatewayProxyIT` asserts zero upstream requests |
| Header stripping, hop-by-hop abuse, spoofed XFF | `proxy/HeaderPolicyTest`, `proxy/ClientIpResolverTest`, `it/GatewayProxyIT.spoofedIdentityAndForwardingHeadersAreReplaced` |
| Signed, audience-bound, scope-filtered identity | `identity/IdentityTokenMinterTest`, `identity/IdentityTokenProperties`, `it/IdentityIT`, `it/GatewayProxyIT.forwardsAllowedRequestFaithfully` |
| JWKS public-only; rotation with overlap | `identity/GatewayKeyStoreTest`, `it/IdentityIT.rotationEndToEnd`, `resilience/LifecycleIT.r12_*` |
| SSRF on write, load and request | `routing/UpstreamAddressPolicyTest`, `it/AdminApiIT.rejectsSsrfUpstreamsOnWrite`, `it/GatewayProxyIT.ssrfBlockedAtRequestTime*`, `it/GatewayProxyIT.neverProxiesToItsOwnListeners` |
| Quota never exceeds RPM, shared across instances, degrades on Valkey failure | `ratelimit/*Properties`, `ratelimit/ValkeyRateLimiterIT`, `resilience/SharedQuotaIT` (R16–R19) |
| Audit minimization, HMAC stability, no tokens/queries/bodies | `audit/AuditTest`, `it/GatewayProxyIT.audit*`, `it/GatewayProxyIT.stripsPrefixAndRoundTripsOpaqueBinaryBody` |
| Append-only audit trail, least-privilege DB role | `store/PostgresConfigStoreIT` |
| DB outage, partition, bad rows, rolling restart | `resilience/*` (R1–R24) |

**Findings during implementation** (fixed, each with a test):
- The "own listener" SSRF rule matched any private address on a gateway port, which blocked a different container on port 8080. It now applies only to this host's addresses (found by the compose smoke test).
- The Lua script was read lazily via the request thread's context class loader, which fails after a context stops. It is now read eagerly from the class's own loader.
- Monitors held across JDBC calls would pin virtual-thread carriers during a DB outage. They are now `ReentrantLock`s.
- Dependency scan: Boot 3.5's managed Tomcat, Jackson, Netty and PostgreSQL driver carried known CVEs. They were pinned to patched versions, then the move to Boot 4.1 removed the need for any pins.

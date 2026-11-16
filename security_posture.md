# Security Posture & Threat Model

The structure follows the [Four Question Framework](https://shostack.org/files/papers/The_Four_Question_Framework.pdf).

## 1. What are we working on?

A reverse proxy that authenticates tenants with Ed25519-signed JWTs, routes by path prefix to upstreams, enforces per-tenant policy, and audits every request. See [docs/architecture.md](docs/architecture.md).

**Assets:**
- The customer-token signing private key (held by the issuer CLI, never by the gateway).
- The visualizer's demo issuer key: a second customer-token key the gateway trusts, held by the hosted visualizer (DigitalOcean) or the dev issuer key locally.
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
7. Gateway instances → Valkey (TLS, ACL user limited to rate-limit keys where the host supports ACLs; DigitalOcean Managed Valkey doesn't, see [G9](#g9)).
8. Visualizer (demo client holding a demo issuer key) → the load balancer and the gateways' public listeners, as a client. It reads gateway management status, the load balancer's read-only stats (HAProxy only) and the stub upstream's request journal. See [docs/visualizer.md](docs/visualizer.md).

## 2. What can go wrong? (STRIDE)

Grouped by STRIDE category, then by element. A threat with two categories sits under the first and names the second.

### S: Spoofing (can someone pretend to be someone else?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Auth | Forged token, `alg: none`, algorithm or token-type confusion, or a token for another issuer/audience accepted | Algorithm pinned to `Ed25519` (`EdDSA`, HS*, `none` rejected); explicit `typ` per token type; `iss` and single-valued `aud` required and checked; `jku`/`x5u`/`jwk` headers ignored ([RFC 8725](https://datatracker.ietf.org/doc/html/rfc8725)) | Implemented |
| Auth | Replay of a stolen token | Short TTL (15 min default, 24h max at issuance); disabling the tenant revokes all its tokens within the poll interval ([G11](#g11)). No per-token revocation yet, see [G2](#g2) | Partial |
| Issuer | Issuer private key stolen, so an attacker can mint a customer token for any tenant *(also Elevation)* | Key file kept outside the repo, and the gateway never holds it. Admin tokens use a separate key, so this doesn't grant admin. Planned: KMS/HSM-held key, `kid` rotation via JWKS. See [G1](#g1) | Partial |
| Proxy | Client spoofs `X-Gateway-*` identity headers to an upstream, or strips the gateway's with `Connection: X-Gateway-Identity` | Client `X-Gateway-*` headers stripped; identity is sent only as a gateway-signed token; hop-by-hop stripping happens before injection (tested) | Implemented |
| Proxy / Audit | Client spoofs `X-Forwarded-For`, `Forwarded` or `X-Real-IP` to fake its IP *(also Repudiation)* | XFF is trusted only from `trustedProxies`; inbound forwarding headers are stripped and rebuilt. On App Platform the recorded IP is the ingress, see [G10](#g10) | Implemented |
| Upstream | Confused deputy: an internal caller claims a tenant identity, or an upstream acts for a tenant it shouldn't *(also Elevation)* | Signed `X-Gateway-Identity` with per-upstream `aud` and 60s TTL; upstreams verify it and do object-level authz ([G19](#g19)). Second hop: token exchange (RFC 8693) + mTLS, deferred, see [G12](#g12), [G17](#g17) | Partial |
| Upstream | Identity token replayed to a different service | `aud` bound to one upstream; short `exp` | Implemented |
| Gateway | Gateway identity key leaks, letting an attacker mint internal identities | Key generated in memory at startup and never written to disk; `kid` rotation via JWKS; 60s TTL limits the value of individual tokens. Keys aren't in a KMS/HSM, see [G18](#g18) | Partial |
| JWKS | JWKS response spoofed, so an upstream trusts an attacker's key | Served on the management listener only; upstreams fetch from a fixed configured URL over TLS and ignore `jku`/`x5u`/`jwk` token headers (tested) | Implemented |
| Visualizer | Anyone who can reach the visualizer mints customer tokens *(also Elevation)* | On DigitalOcean it holds only a demo key the gateway trusts alongside the issuer key (a JWK set); removing it from the set revokes the visualizer, and the real issuer key never leaves the operator. Mints only for `visualizer.allowed-tenants`, TTL ≤ 5 min; HTTP Basic gate, which the `app-platform` profile requires (startup fails without a password); bound to 127.0.0.1 in Compose | Implemented |
| Visualizer | Visualizer's requests trusted as coming from a proxy, so its `X-Forwarded-For` would be believed | Not attached to the `edge` network (the `trustedProxies` subnet); it reaches HAProxy over a separate `client` network | Implemented |

### T: Tampering (can someone change data, requests or config?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Rate limiter | Valkey credentials leaked, or another client resets buckets | TLS, credentials from env/secret, trusted sources. ACL user limited to `rl:*` and script commands where supported (Compose); DigitalOcean Managed Valkey allows only the `default` user, so it uses a cluster dedicated to the gateway. See [G9](#g9) | Partial |
| Proxy | Request smuggling via hop-by-hop / `Transfer-Encoding` | Remove hop-by-hop headers; the JDK client re-frames the body | Implemented |
| Audit | Log injection / tokens in logs *(also Info disclosure)* | Structured JSON encoder; tokens, bodies and query strings never logged | Implemented |
| Audit | Audit log altered or deleted | Structured stdout; on App Platform, forwarded to Managed OpenSearch outside the gateway's control. Not tamper-evident: `doadmin` can delete indexes, and forwarding is best-effort. See [G3](#g3) | Partial |
| Admin API | Unauthorized key rotation | `POST /admin/keys/rotate` requires an admin JWT with `admin:keys` and isn't reachable on the public listener (tested) | Implemented |
| Admin API | Typo in a security flag silently ignored (e.g. `allowPrivateNetwrok`) | Unknown JSON fields rejected with 400 | Implemented |
| Config store | Tampered DB row points a route at an attacker host *(also Elevation)* | Least-privilege DB user, TLS (`verify-full`), upstream URLs re-validated on load, invalid entries skipped (fail closed per route) | Implemented |
| Config store | SQL injection via admin input | Parameterized SQL only | Implemented |
| Config store | Two admins overwrite each other's changes | Optimistic locking (`If-Match` / `version`), 409 on conflict (tested, R5) | Implemented |

### R: Repudiation (can someone deny what they did?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Audit | Tenant denies having made requests | Request ID and tenant ID on every entry | Implemented |
| Audit | Requests Tomcat rejects before the filter chain (illegal request-line characters) leave no audit entry | Encoded `/` passed through to the normalizer for an audited 400; other parser-level rejections still unaudited. See [G8](#g8) | Gap |
| Admin API | Brute-forcing or probing the admin API goes unnoticed | Every admin request, including auth failures, is audited | Implemented |
| Config store | Admin denies making a change | Append-only `config_audit` with actor, time, and before/after values | Implemented |
| Config store | App DB user rewrites the change history | `config_audit` triggers block UPDATE/DELETE/TRUNCATE for every role; the app role gets INSERT/SELECT only (tested) | Implemented |

### I: Information disclosure (can someone see what they shouldn't?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Load balancer | `X-Served-By` and HAProxy stats reveal instance names | Instance names only, no addresses in the header; stats on `:8404` are read-only and not published to the host. In production, drop the header at the edge if instance names are sensitive | Accepted |
| Routing | Authenticated tenant maps routes outside its allowlist (404 vs 403) | Allowlist checked before route lookup | Implemented |
| Proxy | Identity token over-shares: tenant identifiers sent to a third-party upstream, or the caller's unrelated scopes sent to any upstream | Per-route `forwardIdentity: false`; `scope` filtered by per-route `forwardScopes` (tested). See [G20](#g20) | Implemented |
| Proxy | Tenant token leaked to the upstream | Strip `Authorization` before forwarding | Implemented |
| Proxy | `X-Gateway-Instance` tells clients which instance served them (helps target one instance or map the fleet) | Off by default (`gateway.proxy.expose-instance-header`); the value is a random ID, never an address; set last, so an upstream can't forge it. Enabled only on the DigitalOcean demo deployment | Accepted (demo) |
| JWKS | Private key material published by mistake | Only public params serialized; unit test asserts no `d` | Implemented |
| Audit | Logs reveal end-user behavior (e.g. location in the path) | Path minimized **by default** (matched prefix + keyed HMAC); raw path only on per-tenant opt-in | Implemented |
| Audit | Log cluster reachable from the internet (forwarding requires trusted sources off) *(also Tampering)* | TLS and `doadmin` password, rotated after creation; a third-party destination avoids it. See [G4](#g4) | Partial |
| Visualizer | Page leaks a replayable identity token | Signature removed server-side before results reach the browser; only claims are shown | Implemented |

### D: Denial of service (can someone take it down or starve others?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Public listener | Unauthenticated flood (no tenant, so no quota) fills CPU and audit volume | Rejected using in-memory state only, before any DB or Valkey call; the worst case per request is one Ed25519 signature check. Not limited: there's no per-IP or pre-auth rate limit, and every rejection is still audited, so log volume grows with the flood. See [G5](#g5) | Partial |
| Rate limiter | One tenant exhausts shared capacity | Per-tenant token bucket; body-size limit; upstream timeouts on both response headers and body idle time (a stalled body is closed after `timeoutMs` of silence). No per-tenant concurrency limit or total response deadline, see [G13](#g13), [G14](#g14) | Partial |
| Rate limiter | Valkey outage or slowness becomes a gateway outage | 50ms command timeout; degrade to local bucket at RPM ÷ live instances; circuit breaker re-probes (tested, R18–R19); a keep-alive ping and TCP keepalive stop an idle connection from failing the next request (R25). Quotas are approximate meanwhile, see [G9](#g9) | Implemented |
| Rate limiter | Unbounded bucket map growth | Buckets keyed only by authenticated, known tenants | Implemented |
| Proxy | Upstream sends headers then stalls the body, pinning gateway connections; or a body cut off mid-stream reaches the client as if complete *(also Tampering)* | Body idle timeout (`timeoutMs` per read, 504 if nothing was sent yet); a failure after commit closes the client connection with no terminating chunk (tested). See [G14](#g14), [G15](#g15) | Implemented |
| JWKS | Flood of unknown `kid`s forces upstreams to refetch repeatedly | Verifier guidance: cache keys and rate-limit refetches; `Cache-Control` on the response | Implemented |
| Config store / JWKS | DB outage or partition stops traffic or the JWKS | Request path and JWKS use in-memory state only (last-known-good snapshot and key set; keys not expired while the DB is unreachable), so a DB outage only blocks admin writes (tested, R6, R20). A total outage longer than ≈ 8.5 min makes instances not-ready, see [G7](#g7) | Partial |
| Config store | One bad row stops instances from starting | Per-entry validation at load and startup (tested, R13) | Implemented |
| Config store | App role created after the first migration never gets its grants, so a fresh deploy fails in confusing ways | Migration refuses to run until the app role exists; the instance stays not-ready and logs why (tested, R22) | Implemented |
| Visualizer | Visualizer used as a traffic amplifier against the gateway | ≤ 200 requests per run, ≤ 20 in flight, ≤ 3 concurrent runs; the gateway's own quotas still apply | Implemented |

### E: Elevation of privilege (can someone do more than they're allowed?)

| Element | Threat | Mitigation | Status |
|---|---|---|---|
| Routing/policy | Path encoding tricks (`%2F`, `..`, `..;`, `;`, double encoding, `//`, `\`) to bypass the allowlist | One canonical path for allowlist, matching and forwarding (re-encoded); ambiguous paths rejected with 400; property tests | Implemented |
| Routing | Prefix confusion (`/api` matching `/apix`) | Segment-aware matching | Implemented |
| Policy | Write operation allowed with a read-only token | Required scopes per HTTP method; methods without an entry get 405 | Implemented |
| Rate limiter | Scaling out multiplies a tenant's quota *(also DoS)* | Shared Valkey sliding window enforces one quota across all instances (tested, R16–R17); each instance connects to Valkey before it reports ready, so a new instance doesn't count locally on a cold connect (R23) | Implemented |
| Admin API | Stolen admin token | Short TTL (~10 min); least-privilege `admin:routes` / `admin:tenants` / `admin:keys` scopes; every change attributed to `sub` in `config_audit`. No per-token revocation, see [G2](#g2) | Partial |
| Admin API | SSRF via an upstream URL (metadata `169.254.169.254`, loopback, the gateway's own listeners) | Scheme/absolute/no-userinfo/no-query validation; link-local, wildcard and multicast always denied; private and loopback denied unless a route opts in; own listeners always denied; hostname re-resolved and re-checked before every upstream call ([OWASP SSRF](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)). No true connect-time hook, see [G6](#g6) | Partial |
| Admin API | Tenant reaches the admin API, or admin and customer trust roots accidentally share a key | Separate admin listener; admin JWT with its own key, `iss` and `aud=gateway-admin`, so customer tokens are rejected there and vice versa (tested); startup fails if the two key sets share a key, issuer or audience | Implemented |
| Config store | DB credentials leaked | Credentials from env/secret only; DDL only for the migration user; app user `INSERT`/`SELECT` only on `config_audit`; DigitalOcean trusted sources restrict network access | Implemented |
| Supply chain | Known-vulnerable dependency | OSV-Scanner in CI fails the build on any known-vulnerable dependency; Boot 4.1 (supported line) with no version overrides and no ignored advisories | Implemented |

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
- Send large or slow requests up to the configured size and timeout limits. Upstream responses are bounded the same way: `timeoutMs` caps the wait for headers and each gap between body chunks ([G13](#g13), [G14](#g14)).
- Probe routes within its own allowlist.
- Keep using a stolen or leaked token until it expires (no revocation yet, [G2](#g2)).
- Send any payload to its allowed upstreams. The gateway doesn't inspect bodies; that's the upstream's job ([G16](#g16), [G19](#g19)).

## 4. Privacy

See [docs/privacy.md](docs/privacy.md).
- A network observer sees only the IP, hostname, timing and sizes.
- The gateway sees everything, so we minimize what it keeps.
- Tenants can get gateway-blind privacy by encrypting bodies to their upstream's key; the gateway forwards bytes untouched.

## 5. Known gaps / accepted risks
- <a id="g1"></a>**G1. Manual token issuer.** The token issuer is a manual CLI with a file-based key, standing in for a real token service. There is no self-service issuance, no revocation, and no HSM/KMS-backed key. See [docs/identity.md §1](docs/identity.md#1-authentication-customer-and-admin-tokens).
- <a id="g2"></a>**G2. No per-token revocation.** Every token carries a unique `jti`, but the gateway doesn't check it against a denylist. A stolen token stays valid until `exp`: 15 min by default, up to 24h for customer tokens and 1h for admin tokens. The only way to cut it off sooner is to disable or delete the whole tenant. That takes effect within the poll interval (≤ 10s by default), but it also cuts off all of the tenant's legitimate clients.

  Given more time:
  1. A `jti` denylist: an admin endpoint revokes a `jti`, the entry is stored in PostgreSQL with the token's `exp`, and it reaches instances with the config snapshot. Authenticate checks it after the signature, so the request path still makes no network calls. Entries are dropped once the token would have expired anyway, so the list stays small.
  2. Lower the maximum TTL and have clients refresh from a real token service ([G1](#g1)).
  3. Sender-constrained tokens ([DPoP, RFC 9449](https://datatracker.ietf.org/doc/html/rfc9449), or [mTLS-bound, RFC 8705](https://datatracker.ietf.org/doc/html/rfc8705)), so a stolen token is useless without the client's private key.
- <a id="g3"></a>**G3. Audit log not tamper-evident.** Audit logs go to stdout. On App Platform they're forwarded to Managed OpenSearch, but that store isn't append-only or tamper-evident, and delivery is best-effort.
- <a id="g4"></a>**G4. Log cluster reachable from the internet.** App Platform can't forward to an OpenSearch cluster with trusted sources on, so the log cluster is reachable from the internet behind TLS and a password.
- <a id="g5"></a>**G5. Unauthenticated flood.** Quotas are per tenant, so a request without a valid token has no quota. Each one is cheap to reject: in-memory state only, no DB or Valkey call, and at most one Ed25519 signature check. But nothing caps how many arrive:
  - There's no per-IP or pre-authentication rate limit.
  - Every rejection writes an audit entry, so log volume (and OpenSearch storage) grows one-for-one with the flood, and best-effort forwarding may drop real entries.
  - The gateway doesn't limit how many connections one client opens or how slowly it sends (slowloris); it relies on Tomcat's defaults.
  - Volumetric (network-level) floods must be absorbed before they reach the gateway.

  Given more time:
  1. Rate limiting and DDoS protection at the edge (LB, CDN or WAF), which sees all traffic before any instance does.
  2. Fix client-IP resolution ([G10](#g10)), then count authentication failures per IP and temporarily reject IPs over a threshold. The per-IP map must be size-bounded, because attackers choose the keys.
  3. Aggregate audit entries for unauthenticated rejections: one summary line per IP and reason per interval, so the attack is still recorded but log volume stays bounded.
  4. Set Tomcat connection limits and timeouts explicitly (`server.tomcat.max-connections`, `server.tomcat.connection-timeout`) instead of relying on defaults.
- <a id="g6"></a>**G6. SSRF DNS-rebinding window.** SSRF re-checks resolve the upstream host before each call, but the JDK `HttpClient` has no connect hook. A pooled keep-alive connection or a DNS-cache expiry between check and connect leaves a narrow DNS-rebinding window.
- <a id="g7"></a>**G7. Long DB outage makes instances not-ready.** A total DB outage longer than `key-stale-after − token-ttl − skew` (≈ 8.5 min by default) makes every instance report not-ready, because an instance can't tell a partition from a global outage. Raising `key-stale-after` widens the window.
- <a id="g8"></a>**G8. Parser-level rejections unaudited.** Requests rejected by Tomcat's HTTP parser itself aren't audited.
- <a id="g9"></a>**G9. Shared rate-limit trade-offs.** Rate limiting is shared via Valkey. Accepted risks:
  - During a Valkey outage, quotas are approximate (RPM ÷ live instances).
  - Requests an instance admits while degraded aren't recorded in Valkey, so each degraded episode can admit up to RPM ÷ live instances on top of the shared quota. Even a single timed-out command degrades the instance for one probe interval (1s). A keep-alive ping every 10s stops idle connections being dropped, the cause seen on App Platform (65 of 70 at RPM 60, R25).
  - If Valkey loses data, buckets refill to full once.
  - On DigitalOcean Managed Valkey the gateway connects as the `default` user (ACL users aren't supported), so leaked credentials give full access to that cluster. Mitigation: a cluster used only by the gateway, TLS and trusted sources.
- <a id="g10"></a>**G10. Wrong client IP on App Platform.** On DigitalOcean App Platform, the edge puts the client IP in `do-connecting-ip` and the ingress IP in `X-Forwarded-For`. The gateway reads only `X-Forwarded-For`, so the audit `client_ip` there is the ingress address. A configurable client-IP header would fix this.
- <a id="g11"></a>**G11. Eventually consistent config.** Config changes are eventually consistent across instances, bounded by the poll interval (default 10s) in the worst case.
- <a id="g12"></a>**G12. No mTLS to upstreams.** TLS is terminated in front of the gateway; there is no mTLS to upstreams.
- <a id="g13"></a>**G13. No per-tenant concurrency limit.** No per-tenant concurrency limit; slow requests can tie up connections up to the timeout.
- <a id="g14"></a>**G14. No total response deadline.** No total response deadline, only an idle one: an upstream that trickles a byte just under every `timeoutMs` keeps a connection open. SSE or long-poll routes need a `timeoutMs` above their heartbeat interval.
- <a id="g15"></a>**G15. Upstream failure after commit.** If an upstream body fails after the response is committed, the gateway closes the client connection without a terminating chunk, so clients see an error instead of a silently truncated body. The status already sent (e.g. 200) can't be changed.
- <a id="g16"></a>**G16. No body inspection.** No body inspection or WAF.
- <a id="g17"></a>**G17. No second-hop token exchange.** Downstream service-to-service calls (second hop) have no token exchange or mTLS yet, so a downstream service must not reuse the identity token for other services.
- <a id="g18"></a>**G18. Identity keys in process memory.** Gateway identity private keys live in process memory, not a KMS/HSM.
- <a id="g19"></a>**G19. Object-level authorization upstream.** Object-level authorization is the upstream's responsibility; the gateway only enforces path- and scope-level policy.
- <a id="g20"></a>**G20. Operator-configured `forwardScopes`.** `forwardScopes` are configured by the gateway operator. Possible future improvement: upstreams declare the scopes they need per request.
- <a id="g21"></a>**G21. Demo token key in the hosted visualizer.** The hosted visualizer holds a demo customer-token key. Anyone with its password can mint short-lived tokens for its allowlisted tenants. Revoke it by removing the demo key from `GATEWAY_CUSTOMER_PUBLIC_JWK`.
- <a id="g22"></a>**G22. Instance header in the demo deployment.** The DigitalOcean demo deployment enables `X-Gateway-Instance`, so clients can tell instances apart.

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
| DB outage, partition, bad rows, rolling restart | `resilience/*` (R1–R25) |
| Visualizer tenant allowlist, TTL cap, access gate, run limits, signature redaction | `visualizer/.../token/TokenMinterTest`, `config/AccessFilterTest`, `api/AppPlatformProfileIT`, `scenario/ScenarioRunnerTest`, `traffic/GatewayCallerIT`, `scripts/visualizer-smoke.sh` |
| `X-Gateway-Instance` off by default, on every response when enabled, not forgeable by the upstream | `pipeline/GatewayPipelineTest.instanceHeader*`, `pipeline/GatewayPipelineTest.exposedInstanceHeader*`, `it/GatewayProxyIT` |

**Findings during implementation** (fixed, each with a test):
- The "own listener" SSRF rule matched any private address on a gateway port, which blocked a different container on port 8080. It now applies only to this host's addresses (found by the compose smoke test).
- The Lua script was read lazily via the request thread's context class loader, which fails after a context stops. It is now read eagerly from the class's own loader.
- Monitors held across JDBC calls would pin virtual-thread carriers during a DB outage. They are now `ReentrantLock`s.
- Dependency scan: Boot 3.5's managed Tomcat, Jackson, Netty and PostgreSQL driver carried known CVEs. They were pinned to patched versions, then the move to Boot 4.1 removed the need for any pins.

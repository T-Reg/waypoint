# HTTP Services, Gateways, Auth & Multi-Tenancy Checklist

> For real-world precedents behind these items (gateway CVEs, postmortems, attack research), see `api-gateway-lessons.md`.

Language-agnostic. Use for any web service, especially reverse proxies / API gateways, authentication, rate limiting, and multi-tenant systems. Verify protocol claims against the RFCs and OWASP pages linked below.

Primary sources:
- HTTP Semantics: RFC 9110 — `https://www.rfc-editor.org/rfc/rfc9110`
- HTTP/1.1: RFC 9112 — `https://www.rfc-editor.org/rfc/rfc9112`
- 429 Too Many Requests: RFC 6585 §4 — `https://www.rfc-editor.org/rfc/rfc6585#section-4`
- Forwarded header: RFC 7239 — `https://www.rfc-editor.org/rfc/rfc7239`
- JWT: RFC 7519; JWT Best Current Practices: RFC 8725 — `https://www.rfc-editor.org/rfc/rfc8725`
- Problem Details for HTTP APIs: RFC 9457
- OWASP Cheat Sheets — `https://cheatsheetseries.owasp.org/` (REST Security, JWT for Java, Authentication, Logging, SSRF Prevention, Input Validation)
- OWASP API Security Top 10 — `https://owasp.org/API-Security/`

## Contents
1. Authentication
2. Authorization, scopes, tenant isolation
3. Routing and path handling
4. Proxying correctness
5. Rate limiting / quotas
6. Admin / control-plane APIs
7. Audit logging and observability
8. Resilience (timeouts, circuit breakers, canary)
9. Status codes and error responses
10. Threat model prompts

## 1. Authentication
- Every non-public route goes through auth; no handler or path variant reachable before the auth filter (check filter ordering and any early returns).
- Missing/malformed credentials → 401 with `WWW-Authenticate` (RFC 9110 §11.6.1, §15.5.2). Valid credentials but insufficient rights → 403.
- JWT (RFC 8725): algorithm allowlist enforced server-side; reject `none`; key type tied to algorithm (prevent HS256-with-RSA-public-key confusion); verify signature before trusting any claim; validate `exp`, `nbf`, `iat` sanity with small skew; validate `iss` and `aud`; tenant ID taken from a verified claim, never from an unauthenticated header or query param.
- HMAC-signed API keys: signature covers method + path + query + timestamp (+ body hash) to prevent replay/tampering; timestamp window checked; constant-time comparison; key lookup by key ID doesn't leak existence via timing or error text.
- Secrets: signing keys from env/secret store, not source; sufficient length; rotation story (key IDs / `kid`); token issuance tool/endpoint itself protected.
- Error messages don't reveal which part failed in a way that helps attackers (fine to be descriptive in logs).

## 2. Authorization, scopes, tenant isolation
- Scope checks are per-route and default-deny: a route with no configured scope requirement should not silently allow everything unless that's an explicit decision.
- Tenant A cannot affect tenant B: separate quota buckets, no shared mutable per-request state, no cache keyed without tenant, audit entries attributed correctly.
- Tenant identity cannot be overridden by client-supplied headers (`X-Tenant-Id`) after auth; the gateway should strip/overwrite such headers before proxying so upstreams can trust them.
- Strip inbound `Authorization` before forwarding to upstreams unless the upstream needs it (credential leakage to third-party upstreams).

## 3. Routing and path handling
This is where allowlist bypasses hide. Check how the path used for **policy decisions** relates to the path actually **sent upstream** — they must be the same canonical form.
- Normalization: `..` and `.` segments, percent-encoded variants (`%2e%2e`, `%2f`, `%5c`), double slashes, trailing slashes, case sensitivity, matrix params (`;`), null bytes. Decide on a canonicalization (RFC 3986 §6), apply it once, and use the result for both matching and forwarding — or reject ambiguous paths.
- Prefix matching respects segment boundaries (`/api` must not match `/apiadmin`); longest-prefix wins deterministically.
- Query string preserved when proxying; path suffix after prefix joined correctly (no `//` or lost segment).
- Allowlist semantics documented: prefix vs exact vs glob; what does `/v1/*` match?
- Route table updates at runtime are atomic from a reader's perspective (no partially applied update, no `ConcurrentModificationException`).

## 4. Proxying correctness
- Hop-by-hop headers removed (RFC 9110 §7.6.1): `Connection` and any headers it lists, `Keep-Alive`, `Proxy-Connection`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, `Proxy-Authenticate`, `Proxy-Authorization`.
- `Host` header: set to upstream host (or deliberately preserved) — be explicit.
- `X-Forwarded-For` / `Forwarded`: append, don't blindly trust inbound values for client IP decisions (spoofable). Set `X-Forwarded-Proto/Host` if upstreams need them.
- Request method, body, and headers forwarded faithfully; response status, headers (including multiple `Set-Cookie`), and body returned faithfully. Don't rewrite upstream 4xx/5xx into gateway errors.
- Bodies streamed or size-limited; no unbounded buffering. `Content-Length`/`Transfer-Encoding` handled consistently (request smuggling risk if both are passed through inconsistently — RFC 9112 §6.3).
- Redirects from upstream not followed by the gateway (usually) — returned to client.
- Upstream URL scheme/host validated on route creation to limit SSRF (no `file:`, no cloud metadata `169.254.169.254`, no localhost unless intended) — see OWASP SSRF Prevention Cheat Sheet.

## 5. Rate limiting / quotas
- Algorithm is stated and correct: fixed window (allows 2× burst at boundary — acceptable if documented), sliding window log/counter, token bucket. Check math at boundaries and for off-by-one (is the Nth request allowed or rejected?).
- Atomic under concurrency (see language checklist) — a test with parallel requests is the evidence.
- Keyed by tenant (and possibly route), not by client IP when tenants are the unit.
- Unbounded growth: per-key state never evicted → memory leak with many tenants/keys. Use expiry/cleanup.
- Single-instance in-memory limiter: fine for a demo, but note in docs that it doesn't hold across replicas.
- Rejected requests: 429 with `Retry-After` (RFC 6585 §4, RFC 9110 §10.2.3), optionally `RateLimit-*` headers; must not reach upstream; still audited.
- Policy order: auth → path allowlist → scope → quota is typical; decide whether rejected-by-allowlist requests consume quota, and document it.

## 6. Admin / control-plane APIs
- Route/policy management endpoints require separate, stronger auth (admin credential/scope) — a tenant token must not be able to add routes. This is a frequent Critical.
- Admin API not exposed on the same public listener without protection; ideally separate port or network.
- Input validation on route definitions (valid URL, allowed schemes, prefix format, no duplicates/conflicts).
- Changes audited.

## 7. Audit logging and observability
- One structured (JSON) entry per request, including rejected ones and upstream failures: timestamp, request ID, tenant ID, method, path, matched route/upstream, policy decision + reason, upstream status, latency (monotonic clock), client IP.
- Logged on every exit path (use `finally`/middleware wrapping), exactly once.
- No secrets/tokens/full `Authorization` headers in logs; consider PII.
- Log injection: CR/LF in user input can't forge entries (structured encoding handles this).
- Audit write failures don't crash requests, but are noticed; async logging doesn't drop entries silently on shutdown.
- Request ID generated or propagated (`X-Request-Id`) and forwarded upstream.
- Health/readiness endpoint; metrics if in scope.

## 8. Resilience
- Timeouts everywhere: connect, read/response, overall. Default "no timeout" clients are a Should Fix or Critical in a gateway (slow upstream exhausts threads).
- Upstream down → 502; upstream timeout → 504; never hang or 500 with stack trace.
- Circuit breaker (if present): closed → open after threshold within window; open short-circuits with 503; half-open allows limited probes; success closes, failure re-opens. Thread-safe state transitions; per-upstream state. Tests for each transition with an injected clock.
- Canary routing (if present): percentage split is correct statistically and per-tenant; sticky vs random documented; RNG not a shared contention point; weights validated (0–100).
- Graceful shutdown drains in-flight requests.
- Retries: only idempotent methods, bounded, with backoff — retrying POSTs can duplicate side effects.

## 9. Status codes and error responses
- 400 malformed request; 401 unauthenticated; 403 authenticated but forbidden (scope/allowlist); 404 no route; 405 where appropriate; 413 body too large; 429 quota; 502/503/504 upstream issues.
- Consistent error body (e.g. RFC 9457 `application/problem+json`) with a descriptive, non-sensitive reason and a request ID.

## 10. Threat model prompts (authenticated-but-malicious tenant)
Ask of the design, and check the docs describe the answers:
- Can they reach paths outside their allowlist via encoding/normalization tricks?
- Can they impersonate another tenant (header injection, claim confusion, token reuse)?
- Can they exhaust shared resources (connections, threads, memory via unbounded per-key state or huge bodies) and degrade other tenants?
- Can they modify routing/policy (admin API exposure)?
- Can they use the gateway as an SSRF pivot to internal networks?
- Can they read others' data via shared caches or mis-attributed audit logs?
- Can they replay captured tokens/signatures, and for how long?
- What's explicitly out of scope (DDoS at network layer, compromised upstreams) — is that stated as a known gap?

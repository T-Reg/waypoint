# API Gateway Lessons: Real Incidents, CVEs, and Research

Read this when reviewing an API gateway, reverse proxy, auth middleware, rate limiter, circuit breaker, or anything that routes traffic for multiple tenants. Each entry gives a short account of what happened, the general lesson, and **what to check in the code under review**. Production gateways built by large teams have shipped every one of these bugs; a three-hour or three-week build is at least as likely to repeat them.

Use these to sharpen findings and to cite precedent ("this is the same class of bug as CVE-2019-9901 in Envoy"). Precedent makes a finding more convincing and helps calibrate its severity. Still verify that the code under review actually has the issue; similarity alone is not a finding.

Source caveat: links were collected in Nov 2026. CVE aggregator pages vary in accuracy; when a finding depends on exact affected versions, open the vendor advisory or NVD record.

## Contents
1. Path normalization & route-matching bypasses (most common gateway auth bypass)
2. Control-plane / admin API exposure
3. Header trust: X-Forwarded-For, X-Real-IP, hop-by-hop abuse
4. Request smuggling / desync
5. Token validation failures (JWT, signatures)
6. Rate limiting design and races
7. SSRF through routing
8. Resilience: timeouts, retries, circuit breakers, overload
9. Config and route-table changes as an outage source
10. Quick mapping: brief requirement → lessons to apply

---

## 1. Path normalization & route-matching bypasses

**Pattern:** the gateway decides "is this path allowed / authenticated?" on one interpretation of the URL, and the upstream resolves a *different* path. The attacker reaches `/admin` through `/public/../admin`, `/public/%2e%2e/admin`, `/admin;foo`, `//admin`, or a prefix that matches too loosely.

| Incident | What happened |
|---|---|
| **Envoy CVE-2019-9901** | Envoy ≤1.9.0 did not normalize URL paths, so `something/../admin` slipped past a rule blocking `/admin` while the backend resolved the traversal. Fixed in 1.9.1. [NVD](https://nvd.nist.gov/vuln/detail/CVE-2019-9901) |
| **Kong CVE-2021-27306** | The JWT plugin could be bypassed to reach authenticated routes without a valid token; the fix made Kong **always normalize the URI before router matching**. [Wiz entry](https://www.wiz.io/vulnerability-database/cve/cve-2021-27306) |
| **Traefik 2026 middleware bypasses** (CVE-2026-65600, CVE-2026-48020, CVE-2026-67309) | Path rewriting middlewares (ReplacePathRegex, StripPrefix, RewriteTarget) produced paths that the backend normalized differently, so auth middleware on the protected router never ran. Example given: `/api../admin` → rewrite → `/../admin` → backend sees `/admin`. [NVD CVE-2026-65600](https://nvd.nist.gov/vuln/detail/CVE-2026-65600). (Details for the others come from aggregators; verify before citing versions.) |
| **Orange Tsai, "Breaking Parser Logic" (Black Hat USA 2018)** | Showed that proxy + backend combinations (Nginx/Apache in front of Tomcat/Spring) parse `;`, `..`, and encoded segments inconsistently, so access controls are bypassable by default. Explicitly warns about **reverse proxies with Java backends**. [Slides (PDF)](https://i.blackhat.com/us-18/Wed-August-8/us-18-Orange-Tsai-Breaking-Parser-Logic-Take-Your-Path-Normalization-Off-And-Pop-0days-Out-2.pdf) · [Daily Swig summary](https://portswigger.net/daily-swig/multi-layered-systems-cracked-open-by-inconsistent-parsing) |
| **Apache "Confusion Attacks" (Orange Tsai, 2024)** | Follow-up research on semantic ambiguity in Apache HTTP Server modules leading to ACL bypass and RCE. [DEVCORE write-up](https://devco.re/blog/2024/08/09/confusion-attacks-exploiting-hidden-semantic-ambiguity-in-apache-http-server-en/) |

**Lesson:** canonicalize once, early, and use the *same* canonical path for the allowlist check, route matching, and the URL sent upstream. If a path is ambiguous after decoding (contains `..`, encoded `/` or `\`, `;`, NUL, or `//`), rejecting it with 400 is a legitimate and simple choice.

**Check in code:**
- Where does the policy check read the path: raw request URI, decoded path, or framework-normalized path? Is the forwarded URL built from the same value?
- Java specifics: `HttpServletRequest.getRequestURI()` is **not decoded or normalized**; `getServletPath()`/`getPathInfo()` are decoded; `URI.normalize()` removes `.`/`..` but does not decode `%2e`. Spring's `PathPatternParser` and its handling of matrix variables (`;`) and trailing slashes differ by version. Verify against the docs for the versions in use.
- Prefix matching with `startsWith("/api")` also matches `/apiadmin` and `/api../x`.
- Does any rewrite (strip prefix, append suffix) happen *after* the policy check? Rewrites must not produce a path the check never saw.
- Tests: is there at least one test asserting that `..`, `%2e%2e`, `%2f`, double slash, and `;` variants are rejected or correctly denied, and that the upstream stub received **zero** requests?

Severity: an allowlist or auth bypass via path tricks is **Critical**.

## 2. Control-plane / admin API exposure

| Incident | What happened |
|---|---|
| **Spring Cloud Gateway CVE-2022-22947 (CVSS 10)** | When the Gateway Actuator endpoint was enabled, exposed, and unsecured, an unauthenticated attacker could **create a route** via `POST /actuator/gateway/routes/{id}` containing a SpEL expression, call refresh, and get remote code execution. On CISA's Known Exploited Vulnerabilities list. Fixed in 3.1.1 / 3.0.7. [Spring advisory](https://spring.io/security/cve-2022-22947) |
| **Apache APISIX CVE-2022-24112** | A bug in the `batch-requests` plugin let attackers overwrite `X-REAL-IP`, bypassing the Admin API's IP restriction. With the **default admin API key** this became RCE. On CISA KEV. Fixed in 2.12.1 / 2.10.4. [APISIX advisory](https://apisix.apache.org/blog/2022/02/11/cve-2022-24112) |

**Lesson:** the API that changes routes is the gateway's most dangerous surface, because whoever controls routing controls where every tenant's traffic (and credentials) go. Dynamic routing at runtime is exactly what the brief asks for, so this is very likely in scope.

**Check in code:**
- Are route add/update/delete endpoints authenticated with a **distinct admin credential or scope**, not just any valid tenant token? A tenant token able to change routes is **Critical**.
- Is the admin API on a separate port or path that the proxy itself won't forward? Can the proxy route a request *to* its own admin API (loopback upstream)?
- Default or hardcoded admin keys in config or README are **Critical** if they would ship as-is.
- Is route input validated (scheme allowlist, well-formed URL, no expression languages or templates evaluated from user input)?
- Are route changes written to the audit log?

## 3. Header trust: X-Forwarded-For, X-Real-IP, hop-by-hop abuse

| Incident / research | What happened |
|---|---|
| **APISIX CVE-2022-24112** (above) | Spoofable `X-REAL-IP` defeated an IP-based admin restriction. |
| **Spring Cloud Gateway CVE-2025-41235 and CVE-2026-47825** | The gateway forwarded `X-Forwarded-For` / `Forwarded` headers from **untrusted proxies**. The 2026 fix introduced `spring.cloud.gateway.trusted-proxies`. Fixed in 4.2.3 (2025), and in 3.1.13 / 4.1.13 / 4.2.9 / 4.3.5 / 5.0.2 (2026). [GitLab advisory](https://advisories.gitlab.com/pkg/maven/org.springframework.cloud/spring-cloud-gateway-server/CVE-2025-41235/) · verify at spring.io/security |
| **Hop-by-hop header abuse (Nathan Davison)** | Clients can list arbitrary header names in `Connection:`, and RFC-compliant proxies strip those headers before forwarding. Attackers use this to make the proxy **remove headers the gateway added**, such as the tenant ID, `X-Forwarded-For`, or auth context, so the backend sees a request "from the proxy itself" or with no identity. Akamai and Varnish (CVE-2022-45059) were affected. [Original post](https://nathandavison.com/blog/abusing-http-hop-by-hop-request-headers) · [Akamai mitigation](https://www.akamai.com/blog/security/akamai-mitigates-hop-by-hop-header-abuse) |
| **Client-IP pitfalls** | Using the leftmost XFF entry lets anyone impersonate any IP; correct parsing walks from the right, skipping known proxies. [engineered.at: The perils of the "real" client IP](https://engineered.at/articles/the-perils-of-the-real-client-ip) |

**Check in code:**
- Does the gateway **strip or overwrite** inbound `X-Tenant-Id`, `X-User`, `X-Scopes`, `X-Forwarded-*`, `X-Real-IP`, and `Forwarded` before adding its own? Upstreams will trust these headers.
- When removing hop-by-hop headers, does it also remove headers *named in* `Connection`? And does it re-add the gateway's own identity headers **after** that stripping, so `Connection: X-Tenant-Id` cannot delete them?
- Are any policy decisions (rate limits, admin access) keyed on a client-supplied IP header?

## 4. Request smuggling / desync

James Kettle's research ([HTTP Desync Attacks, 2019](https://portswigger.net/research/http-desync-attacks-request-smuggling-reborn); [HTTP/1.1 must die, 2025](https://portswigger.net/research/http1-must-die)) shows that when a front-end and back-end disagree on where a request ends (`Content-Length` vs `Transfer-Encoding`), attackers can prepend data to *another user's* request, bypassing the gateway's checks entirely. His 2025 conclusion is that this is a protocol-level problem with HTTP/1.1 upstream connections, so prefer HTTP/2 to upstreams where possible.

**Check in code:**
- Does the proxy copy `Content-Length` and `Transfer-Encoding` verbatim, or let the HTTP client compute framing? A request with both headers, or with a duplicated or invalid `Content-Length`, should be rejected (RFC 9112 §6.3).
- Is the request body re-framed by a well-tested client library (e.g. `java.net.http.HttpClient`, which rejects the restricted headers `Content-Length`/`Transfer-Encoding`/`Connection` by default — usually a good thing), or written by hand?
- Hand-rolled HTTP parsing in a gateway is a **Should Fix** at minimum. Mention desync risk and suggest relying on the framework.

## 5. Token validation failures

| Incident / research | What happened |
|---|---|
| **`alg: none` and algorithm confusion (Auth0, 2015)** | Many JWT libraries accepted `alg: none` tokens as valid, or verified an HS256 token using the RSA *public* key as the HMAC secret. [Auth0 write-up](https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/). This recurred years later in Auth0's own Node library (CVE-2022-23540), and some fixes only blocked the lowercase string "none". |
| **Java "Psychic Signatures" CVE-2022-21449** | Java 15–18's ECDSA verifier accepted a **blank signature (r = s = 0)** as valid, so ES256 JWTs, SAML assertions, and TLS handshakes could be forged. Found by Neil Madden; patched in the April 2022 CPU. [JFrog analysis](https://jfrog.com/blog/cve-2022-21449-psychic-signatures-analyzing-the-new-java-crypto-vulnerability/) |
| **JWK / JKU / `kid` injection** | Servers that trust a key embedded in the token header (`jwk`), fetch keys from a header-supplied URL (`jku` → SSRF), or use `kid` as a file path or SQL key. [PortSwigger: algorithm confusion](https://portswigger.net/web-security/jwt/algorithm-confusion) · [PortSwigger JWT topic](https://portswigger.net/web-security/jwt) |
| **"Three New Attacks Against JSON Web Tokens" (Tervoort, Black Hat USA 2023)** | Newer JWT implementation flaws. [Whitepaper (PDF)](https://i.blackhat.com/BH-US-23/Presentations/US-23-Tervoort-Three-New-Attacks-Against-JSON-Web-Tokens-whitepaper.pdf) |

**Check in code:**
- Is the accepted algorithm **pinned server-side** (e.g. only HS256 or only RS256/ES256), ignoring the token's `alg` header for key selection?
- Is the key never taken from the token itself (`jwk`, `jku`, `x5u`)? Is `kid` only a lookup key into a fixed map?
- If ECDSA is used on the JVM, is the runtime version past the CVE-2022-21449 fix? Check the Dockerfile and CI `java-version`.
- Are `exp`, `nbf`, `iss`, and `aud` validated? Is the tenant ID taken only from verified claims?
- For HMAC API keys: constant-time comparison (`MessageDigest.isEqual`), and a timestamp included in the signed string to bound replay.

## 6. Rate limiting design and races

| Source | Lesson |
|---|---|
| **Stripe, "Scaling your API with rate limiters"** | Stripe runs four limiters: request rate (token bucket per user), concurrent requests, and two load shedders. Use clear 429 vs 503 semantics, and build a **kill switch** so a buggy limiter can be turned off. [stripe.com/blog/rate-limiters](https://stripe.com/blog/rate-limiters) |
| **Cloudflare, "How we built rate limiting capable of scaling to millions of domains" (2017)** | Fixed windows allow up to 2× bursts at the window boundary; Cloudflare uses a **sliding-window counter**, two integers per key weighted by elapsed fraction, which is accurate enough and cheap. [blog.cloudflare.com](https://blog.cloudflare.com/counting-things-a-lot-of-different-things/) |
| **GitHub, sharded replicated rate limiter in Redis** | Moving limiter state out of process for multi-instance correctness, and the operational trade-offs. [github.blog](https://github.blog/engineering/infrastructure/how-we-scaled-github-api-sharded-replicated-rate-limiter-redis/) |
| **PortSwigger, "Smashing the state machine" (2023)** | The HTTP/2 **single-packet attack** lands 20–30 requests at the same moment, making check-then-act races reliably exploitable remotely. A limiter that reads the count, compares, then increments in separate steps can be overrun. [Research](https://portswigger.net/research/smashing-the-state-machine) |
| **OWASP API Security Top 10 2023, API4: Unrestricted Resource Consumption** | Missing limits on request rate, payload size, and expensive operations. [owasp.org/API-Security](https://owasp.org/API-Security/) |

**Check in code:**
- Is "check quota + record request" a **single atomic operation** per tenant? With concurrent requests, can N+k requests pass a limit of N? Ask for, or write, a parallel test.
- Is the algorithm named and documented, including its boundary behavior (fixed-window burst)? Is the Nth request allowed and the N+1th rejected (off-by-one)?
- Is per-tenant limiter state bounded or evicted? An entry per unknown key or IP grows without bound, which is itself an API4 issue.
- Do rejected requests get 429 with `Retry-After`, get audited, and never reach upstream?
- Is it documented that an in-memory limiter is per instance, so N replicas give N× the quota?

## 7. SSRF through routing

**Capital One (2019):** widely attributed to SSRF through a misconfigured WAF/proxy that reached the EC2 metadata service (`169.254.169.254`), took IAM credentials, and read S3. AWS's IMDSv2 (session token via `PUT`) was the industry response. Good overviews: [TechTarget](https://www.techtarget.com/searchsecurity/news/252467901/Capital-One-hack-highlights-SSRF-concerns-for-AWS), [CloudSEK](https://www.cloudsek.com/knowledge-base/capital-one-data-breach). Prevention guidance: [OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

A gateway is by design a machine that makes requests to configured URLs, so the route table is an SSRF primitive for whoever controls it (see §2). The APISIX and Spring Cloud Gateway chains above both went admin access → route creation → code execution or SSRF.

**Check in code:**
- Upstream URL validation: scheme allowlist (`http`/`https`), and optionally a host allowlist; block link-local, loopback, and metadata ranges unless intended. Note DNS rebinding: validating a hostname at route-creation time doesn't pin the IP it resolves to later.
- Can a tenant influence the upstream host via `Host` or `X-Forwarded-Host` headers, or via the path (e.g. `/proxy/http://internal`)?
- Does the HTTP client follow redirects? An upstream `302` to an internal address turns into SSRF. Java's `HttpClient` default is `Redirect.NEVER`; verify the configured value.

## 8. Resilience: timeouts, retries, circuit breakers, overload

| Source | Lesson |
|---|---|
| **AWS Builders' Library, "Timeouts, retries, and backoff with jitter"** | Every remote call needs a timeout. Choose it from a target false-timeout rate (e.g. p99.9 of downstream latency). Retries are "selfish" and amplify load. Use capped exponential backoff with jitter, never retry non-idempotent calls blindly, and limit retries (token bucket). [aws.amazon.com/builders-library](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter) |
| **Google SRE book, ch. 21 "Handling Overload" and ch. 22 "Addressing Cascading Failures"** | Overload is the most common cause of cascading failure. Covers load shedding, bounded queues, propagated deadlines, and retry amplification. [Ch. 21](https://sre.google/sre-book/handling-overload/) · [Ch. 22](https://sre.google/sre-book/addressing-cascading-failures/) |
| **Martin Fowler, "CircuitBreaker"** | The canonical closed → open → half-open state machine: after a reset timeout, a **trial call** either resets the breaker or restarts the timeout. [martinfowler.com/bliki/CircuitBreaker.html](https://www.martinfowler.com/bliki/CircuitBreaker.html) |
| **Resilience4j** | The standard JVM implementation: count- or time-based sliding windows, minimum-calls before evaluation, and `permittedNumberOfCallsInHalfOpenState`. A known issue showed half-open behaving unexpectedly under extreme concurrency, so half-open probe limits must be enforced atomically. [Docs](https://resilience4j.readme.io/docs/circuitbreaker) · [Issue #903](https://github.com/resilience4j/resilience4j/issues/903) |

**Check in code:**
- Connect *and* request timeouts on the upstream client. A missing timeout is **Should Fix**, or **Critical** if a slow upstream can exhaust the gateway's thread pool and take down every tenant. Is upstream timeout → 504 and connection failure → 502?
- Circuit breaker (if built): Is state kept per upstream? Are transitions thread-safe? Does half-open allow only the configured number of probes, even under concurrency? Does the breaker need a minimum number of calls before it trips, so one early failure doesn't open it? Do tests use an injected `Clock` rather than `Thread.sleep`?
- Is the failure definition sensible? Typically connection errors, timeouts, and 5xx count as failures; 4xx do not.
- Retries: only idempotent methods, bounded, with jitter. A gateway that retries POSTs is **Should Fix**.
- One slow tenant or upstream shouldn't starve others (bulkheads: separate pools, or per-upstream concurrency limits).

## 9. Config and route-table changes as an outage source

**Cloudflare, 18 Nov 2025:** a database permissions change produced an oversized, automatically regenerated configuration file. It propagated globally within minutes and exceeded a limit in the core proxy, which then crashed, causing widespread 5xx errors. [Cloudflare post-mortem](https://blog.cloudflare.com/18-november-2025-outage/). **5 Dec 2025:** a change pushed through the global configuration system hit a latent bug in the older proxy. [Post-mortem](https://blog.cloudflare.com/5-december-2025-outage/).

**Lesson:** a gateway's hot-reloadable config is a production change channel. Treat route and policy updates as untrusted input: validate them, apply them atomically, and make sure a bad update fails closed for that route rather than crashing the process.

**Check in code:**
- Does a malformed route or policy update get rejected with 400, leaving the previous table in place? Or can it throw inside the request path, or leave a half-applied state?
- Are updates applied atomically (copy-on-write swap of an immutable table) so in-flight requests see the old or new table, never a mix?
- Are there size or count limits on routes and policies?
- Can you roll back, or at least see what changed and when (audit entries for control-plane changes)?

## 10. Quick mapping: brief requirement → lessons to apply

| Requirement in a gateway brief | Sections |
|---|---|
| Token authentication, tenant identity | §5, §3 (tenant headers can't be spoofed or stripped) |
| Dynamic routing at runtime | §2 (admin auth), §7 (SSRF), §9 (atomic, validated updates), §1 (prefix matching) |
| Path allowlist | §1 (normalization), canonical path shared by check and forward |
| Per-minute quota | §6 (atomicity, algorithm, eviction, 429) |
| Scope checks | §5 (scopes only from verified claims), default-deny |
| "Must not reach the upstream" on violation | §1, §4, plus a test asserting the upstream stub got zero calls |
| Audit log for every request | Audit rejections, upstream failures, and control-plane changes; never log tokens |
| Threat model: authenticated-but-malicious tenant | §1, §2, §3, §6, §7 are exactly what such a tenant would try |
| Circuit breaker extension | §8 |
| Canary routing extension | Weighted split must be validated (0–100), per-tenant, and not let a tenant choose the canary via a header unless intended; canary upstream gets the same policies and timeouts as primary |
| Deployment | §7 (metadata endpoints on cloud VMs), admin port not publicly exposed, secrets via env |

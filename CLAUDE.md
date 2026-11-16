This is a project for an interview for Digital Ocean's Security Products team.

The prompt is described in CodeBash.md in this same directory.

# How we work together
- Do NOT access the digital ocean account in any way - through browser nor API access - you shouldn't have access anyway.  Do give me advice and links to public docs about how we should set it up.  Do give me example commands to run or portal UI instructions to do it myself.  But, leave the actual infrastructure changes to me to do manually.

# Code Clarity/Style Hints
- Use code comments sparingly and instead opt for self-documenting code.  When code comments are needed to describe context that code cannot make clear, keep it concise and clear.  Try to keep it less than or equal to 2 lines maximum.
- Always verify with me before making changes, and be clear and concise about what changes you plan to make.  I need to be in the know for all changes you plan to make so I can review it and approve it before you make the change in the code.
- Before committing, Do a thorough code using my code review skill.  Make sure no code is committed that has not been reviewed.

# Doc map
- `docs/design.md`: overview: glossary, pipeline, listeners, routing, policy, status codes, proxying, audit, extension seams, decisions. Read this before implementing anything.
- `docs/identity.md`: customer/admin tokens, identity tokens to upstreams, JWKS and key rotation.
- `docs/multi-instance.md`: PostgreSQL config store, propagation, lifecycle (reboot/scale), shared Valkey rate limiting.
- `docs/privacy.md`: confidentiality and traffic privacy.
- `docs/architecture.md`: Mermaid request-flow diagram (required deliverable). Keep it in sync with the pipeline.
- `docs/testing.md`: test case catalog (identity/JWKS, resilience R1–R20, compose smoke). Keep test cases there, not in design.md.
- `docs/research.md`: reading list and takeaways.
- `security_posture.md`: threat model (required deliverable).
- `README.md`: public-facing summary.

# Stack
Java 21, Spring Boot 4.x (MVC on virtual threads), Maven wrapper, JDK HttpClient for upstreams, Nimbus JOSE+JWT (EdDSA/Ed25519), PostgreSQL + Flyway + HikariCP, Valkey via Lettuce (Spring Data Redis) for shared rate limiting, Jackson 3 (`tools.jackson`). Tests: JUnit 6, jqwik, WireMock, Testcontainers (PostgreSQL, Valkey, Toxiproxy), Awaitility, JaCoCo.

# Testing rules
- Every behavior change ships with its tests in the same change. No untested branches in auth, policy, routing or proxy code.
- Unit tests cover every rejection branch: missing/malformed/expired/not-yet-valid token, wrong alg (`none`, HS256), wrong `typ`, wrong or multi-valued `iss`/`aud`, missing `tid`, unknown or disabled tenant, path not allowed, method not configured (405), missing scope for the method (including `*` fallback), quota exceeded, SSRF-blocked upstream IP.
- Admin API tests: customer token (`aud=gateway`) rejected on the admin listener; admin token (`aud=gateway-admin`) rejected on the public listener; each `admin:*` scope gates only its endpoints (403 `admin.scope_missing`); the admin `sub` lands in `updated_by` and `config_audit`.
- Identity token `scope` is filtered by `forwardScopes` (including the default namespace rule). Test that unrelated scopes never reach the upstream.
- Audit log: by default, entries contain `path_prefix` + `path_hmac` and **no** raw `path`. With `auditRawPath: true` the raw path appears. The HMAC is stable for the same path and key, and changes when the key rotates.
- Integration tests run the full proxy path against a WireMock upstream. Every rejection case must assert the upstream received **zero** requests. They also cover header stripping/injection, hop-by-hop removal, streaming, timeouts (504) and connect failures (502). They also verify the `X-Gateway-Identity` token the upstream receives: signature (checked via the JWKS endpoint), `aud` equal to that route's audience, `tid`/`sub`/`scope`, and a short `exp`.
- The JWKS endpoint and key rotation are in scope and need the full unit and integration suite listed in `docs/testing.md` ("Identity token & JWKS"). Integration tests must verify identity tokens using keys fetched from the **running gateway's** JWKS, as a real upstream would, and must cover rotation end to end.
- The Valkey Lua token-bucket script gets its own tests against a real Valkey (Testcontainers), with a `now_ms` override: refill math, burst up to capacity, `Retry-After` accuracy, key expiry. A jqwik property checks that across N simulated instances the total allowed never exceeds RPM in any 60s window.
- Multi-instance resilience tests R1–R20 in `docs/testing.md` are required (JUnit tag `resilience`, Testcontainers PostgreSQL + Valkey + Toxiproxy, multiple gateway contexts against one DB). They cover reboot, scale-up, scale-down, crash, DB outage, propagation and the zero-error rolling restart. Every change to config storage, propagation, startup/shutdown or key registration must keep them passing and add a scenario if it introduces a new failure mode.
- Property tests (jqwik) for invariants:
  - Path normalizer: idempotent; never outputs `..`, `//`, `;`, `%` or `\`; generators include `..;`, `%252e`, `%2F` and backslashes; the allowlist decision is the same for every encoding of the same path.
  - Prefix matcher: agrees with a simple reference implementation.
  - Token bucket: never allows more than RPM in any 60s window, using an injected `Clock`.
  - Route-table swaps: a reader always sees a consistent snapshot under concurrent writes.
- Tests must be deterministic: no `Thread.sleep`, no real clock (inject `java.time.Clock`), no fixed ports. For cross-instance convergence, use Awaitility with an explicit bounded timeout.
- Coverage gate in JaCoCo: line ≥ 85% and branch ≥ 80%. Raise it as the code matures; never lower it to make a build pass.

# CI must evolve with the code
- Any new test type, tool, build step, module or Maven profile gets wired into `.github/workflows/ci.yml` in the same change.
- CI runs:
  - `./mvnw verify` (unit + property + integration + `resilience` + coverage gate);
  - a dependency vulnerability scan;
  - a Docker build;
  - a container smoke test (start the gateway, issue a token, call a stub upstream, check that a denied request returns 403);
  - the `compose-smoke` rolling-restart job (Postgres + Valkey + 2 gateways + HAProxy, zero errors under load).
- Pin action versions. Lint the workflow with `actionlint` before committing workflow changes.
- CI must fail on any test failure or coverage drop. Never add `continue-on-error` to test steps.

# Docs upkeep
- README stays public-facing, concise and high-level, and keeps the brief's sections: setup, token issuance, security assumptions & known gaps. Design detail goes in `docs/`, not the README. Update the README in the same change whenever setup, tokens or gaps change.
- Any change touching auth, routing, policy, proxy headers, the audit log or the admin API must update `security_posture.md` (STRIDE table, can/cannot lists, known gaps) in the same change.
- Pipeline changes must update `docs/architecture.md`.

# Extensions
Circuit breaker, canary routing and deployment are not built yet. Keep the seams in `docs/design.md` §10 (`UpstreamClient` decorator, `UpstreamSelector`) and the `RateLimiter` / store interfaces (`docs/multi-instance.md`) intact so they can be added without restructuring.
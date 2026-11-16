---
name: deep-code-review
description: Extremely thorough code review that verifies claims against official public documentation (JDK Javadoc, Spring, RFCs, OWASP, library docs) and checks the code against the project's architecture/design docs and threat model in both directions, and reports every issue ordered by severity — Critical, Should Fix, Low, Nit — each with file:line, impact, a concrete fix, and a source link. Defaults to Java (Maven/Gradle, Spring Boot, JUnit) but works for any language. Use this skill whenever the user asks to review, audit, critique, sanity-check, or "look over" code, a diff, a branch, a PR, a project, or a take-home/interview submission; asks "is this production-ready?", "what's wrong with this?", "anything I missed?", or "check my work before I submit"; asks whether the code matches the architecture, design doc, or threat model; or wants feedback on correctness, security, concurrency, efficiency, readability, tests, or CI — even if they don't say the word "review".
---

# Deep Code Review

The goal is the review a meticulous senior engineer would give if they had unlimited time and a browser open to the docs: every real problem found, every claim about library or protocol behavior checked against a primary source, and the result ranked so the reader knows exactly what to fix first.

Two failure modes matter equally:
- **Missing a real bug** because you skimmed, assumed, or trusted memory about how an API behaves.
- **Reporting a false positive** with confidence. A wrong "Critical" wastes the reader's time and erodes trust in every other finding.

Thoroughness and verification are how you avoid both. Don't rush to output.

Do not modify the code during the review. Review first; offer to fix afterward.

## 1. Establish scope and context

Figure out what is being reviewed:
- A diff/branch/PR → `git diff <base>...HEAD`, `git log <base>..HEAD`, `gh pr diff <n>` if a PR number is given. Still read the surrounding code of every touched function — bugs often live at the boundary between changed and unchanged code.
- Specific files or a whole project → enumerate every source, test, build, config, and CI file (`git ls-files`, or `find` excluding build output like `target/`, `build/`, `node_modules/`).
- If unclear, default to: uncommitted changes if any exist, otherwise the whole repository.

Then gather intent before judging anything:
- Read the README, any brief/spec/assignment file (e.g. a markdown task description in the repo root), CLAUDE.md, ADRs, and the build file (`pom.xml`, `build.gradle(.kts)`, `go.mod`, `package.json`, `pyproject.toml`).
- Note language/runtime **versions** and library **versions** — documentation must be checked for the version actually in use, since behavior and defaults change between versions.
- If there is a spec or brief, extract its explicit requirements into a checklist. Unmet requirements are findings (often Critical or Should Fix — a missing required deliverable is as bad as a bug).
- Find every design document. Look for `ARCHITECTURE.md`, `DESIGN.md`, `SECURITY.md`, `THREAT_MODEL.md`, `docs/**/*.md`, `adr/` or `decisions/`, and README sections on architecture or security. Search case-insensitively (e.g. `git ls-files | grep -iE 'arch|design|adr|threat|security|spec|rfc'`). Also include diagrams, whether embedded (Mermaid, PlantUML, ASCII) or image files. These feed the conformance check in §4.

## 2. Build and run what you can

Evidence beats inspection. Where possible and safe:
- Build and run the test suite (`./mvnw -q verify`, `./gradlew check`, `go test ./...`, `npm test`, `pytest`). Record failures, warnings, and flaky behavior.
- Run available static checks (compiler warnings with `-Xlint:all`, `go vet`, linters already configured in the project).
- When you suspect a bug, try to prove it: write a small throwaway test or script in the scratchpad (not the repo) that triggers it. A finding marked **Verified by reproduction** carries far more weight.
- Don't install heavyweight tooling or make network calls to the project's real dependencies without asking.

**Measure test coverage.**
- Run the coverage report (Java: `./mvnw verify`, then read `target/site/jacoco/jacoco.xml` or `index.html`; Go: `go test -coverprofile`; JS/TS: `c8`/`nyc`; Python: `pytest --cov`).
- Report line and branch coverage overall and for each security-critical class or package (auth, policy, routing, proxy, rate limiting).
- Uncovered **rejection or error branches** in those packages are **Should Fix**. A coverage gate that is configured but not enforced in CI is **Should Fix**.
- If time permits and the build supports it, run mutation testing (e.g. PIT `pitest-maven`) on the auth and policy packages, and report surviving mutants as missing assertions.

If you can't build or run something, say so in the report's "Limits of this review" section rather than silently skipping it.

## 3. Read everything in scope — in multiple passes

Read each file fully. Don't sample. For larger codebases, keep a running list of files reviewed so nothing is skipped.

Make separate passes, because each one needs a different mindset:

1. **Correctness** — logic errors, off-by-one, null/empty/negative/huge inputs, error paths, unhandled exceptions, wrong status codes, edge cases in parsing, time zones and clocks, integer overflow, incorrect equality, state that leaks between requests.
2. **Concurrency** — shared mutable state, check-then-act races, non-atomic compound operations on concurrent collections, visibility (missing `volatile`/happens-before), lock scope, deadlock, thread-pool starvation, blocking calls on event-loop/reactive threads.
3. **Security** — authn/authz bypasses, injection, path traversal and normalization tricks, SSRF, secrets in code/logs, timing-unsafe comparisons, weak crypto, missing input limits (DoS), header trust (`X-Forwarded-For`), unsafe deserialization, dependency CVEs. Think like an authenticated-but-malicious user: what can they reach that they shouldn't?
4. **Resources and efficiency** — unclosed streams/connections, clients created per request, unbounded maps/queues/caches (memory growth per tenant/key), missing timeouts, buffering whole bodies when streaming is needed, N+1 calls, needless allocations or copies on hot paths, algorithmic complexity.
5. **API and library usage** — is each library/JDK/framework call used the way its docs say? Defaults, thread safety guarantees, exception contracts, deprecated APIs. **This pass is where documentation lookups happen** (see §5).
6. **Readability and design** — naming, function size, duplicated logic, dead code, leaky abstractions, unclear ownership, magic numbers, comments that lie, inconsistent error handling style.
7. **Tests** — do tests exercise the risky paths (rejections, failures, boundaries, concurrency) or just the happy path? Are assertions meaningful? Flakiness (`Thread.sleep`, real clocks, port collisions, test order dependence)? Are integration tests actually integrating? Specifically check:
   - Every rejection/error branch in the code has a test that triggers it (cross-check against the coverage report from §2).
   - Property-based tests exist wherever an invariant exists: parsers/normalizers, matchers, rate limiters, and concurrent state swaps.
   - Proxy/gateway integration tests assert the upstream was **not** called on a denial, not just the status code.
   - The tests would actually fail if the logic broke. Mentally invert a condition: would a test go red?
   - Every new behavior in the diff has a matching new test.
8. **Build, CI, ops, docs** — pinned versions, reproducible builds, CI actually runs the full suite and fails on failure, config via env, health checks, structured logging, graceful shutdown, README accurate (do the documented commands work?). Specifically check:
   - CI runs **every** test category present in the repo. Compare the test source sets, Maven/Gradle profiles and test tags against the workflow steps; a test category that exists but isn't run in CI is a finding.
   - The coverage gate is enforced in CI, and no test step has `continue-on-error`.
   - The workflow is linted with `actionlint` if available.
   - Docs required by the brief (diagram, threat model, README sections) are present and consistent with the code.

For language-specific checklists, read the matching reference file:
- Java / JVM → `references/java.md` (read this by default unless the code is clearly another language)
- HTTP services, gateways, proxies, auth, rate limiting, multi-tenancy → `references/http-services-security.md`
- API gateways / reverse proxies specifically (routing, tenant policies, admin APIs, circuit breakers, canary) → also read `references/api-gateway-lessons.md`. It catalogs real CVEs, postmortems, and research (Envoy, Kong, Traefik, Spring Cloud Gateway, APISIX, Cloudflare, Stripe, PortSwigger) with what to check for each. Cite the matching precedent in findings; it makes severity concrete and the finding more convincing.
- Other languages → apply the passes above using your own knowledge, verified against that language's official docs.

## 4. Check the code against the design documents

The architecture and design docs are a contract. Reviewers and the authors' teammates trust them to describe what the system does, and threat models especially are read as promises ("a tenant cannot X"). Code drifts from design quietly. A shortcut taken under time pressure can open a gap that the design explicitly claims is closed, and nobody notices because the docs still look right. Treat this as its own pass, done **after** you've read the code (so you know what it really does) and **before** you finalize findings.

If no design docs exist but the brief requires them (e.g. an architecture diagram or threat model), that absence is itself a finding (§3 pass 8). Then use the README and code comments as the stated design, and do the rest of this pass against those.

### 4a. Build a claims ledger from the docs

Read every design document fully and extract each **checkable claim** into a working list. A claim is any statement that the code could contradict. Typical kinds:

- **Components and boundaries.** Which modules or classes exist, what each owns, what talks to what, and which dependencies or datastores are used.
- **Request flow and ordering.** E.g. "auth → path allowlist → scope check → quota → proxy → audit". Ordering matters: quota counted before auth, or audit skipped on early rejection, are real gaps.
- **Failure branches.** Every error edge in a diagram: which condition, which status code, which error body, and whether the upstream is contacted.
- **Data contracts.** Audit log fields, token claims, config and route schemas, API endpoints and their payloads, headers added or stripped.
- **Policies and defaults.** Default-deny vs default-allow, quota algorithm and window, timeout values, size limits, clock skew, retry rules.
- **Concurrency and state model.** In-memory vs external store, how route updates are applied (atomic swap?), per-instance vs shared limits.
- **Security claims and threat model.** Every "can"/"cannot" statement about an attacker, every listed mitigation, and every listed **known gap**.
- **Non-functional claims.** Latency, statelessness, horizontal scaling, graceful shutdown, observability.
- **Extensions.** Circuit-breaker thresholds and half-open behavior, canary split rules, deployment topology.

For diagrams: Mermaid, PlantUML and ASCII can be read as text; read image files with the Read tool. Translate every arrow and every failure branch into a claim, because diagrams usually carry the most precise flow information and are the most likely to go stale.

### 4b. Trace each claim into the code (doc → code)

For each claim, find the exact enforcement point (`file:line`) and the test that proves it. Classify it:

- **Implemented**: the code does it on every path, and a test exercises it.
- **Implemented, untested**: the code does it but nothing would catch a regression. Usually **Should Fix** for security or policy claims, **Low** otherwise.
- **Partial**: true on the main path but not everywhere. Check alternate entry points (admin API, health/metrics endpoints, error handlers, streaming paths, retries, the canary upstream, startup or reload paths). Partial enforcement of a security claim is a gap, not a pass.
- **Diverges**: the code does something different from what the doc says (different order, status code, algorithm, default, field name).
- **Missing**: the doc describes it, and the code doesn't have it.

Be adversarial with security claims. For "a tenant cannot access paths outside its allowlist", don't stop at finding the allowlist check. Ask whether any request can reach the proxy without passing through it: encoded paths, a route added later, an exception handler that falls through, a filter ordering issue. The lessons in `references/api-gateway-lessons.md` are a good source of bypass ideas.

### 4c. Sweep the code for undocumented behavior (code → doc)

Gaps are often introduced by things the design never mentions. Walk the code's surfaces and check each one appears in the design:

- Every HTTP endpoint and listener, including admin, debug, health, metrics and actuator endpoints, and any test-only endpoint that ships in production code.
- Every place a request can bypass or short-circuit the documented pipeline: early returns, `permitAll`-style exemptions, feature flags, fallback branches, `catch` blocks that continue instead of rejecting.
- Every outbound call and dependency (new upstream clients, external services, files written).
- Every header added, stripped or trusted; every identity or tenant source.
- Config options and defaults that change security posture (e.g. a flag to disable auth, a permissive default).
- Behavior the threat model doesn't account for. If code adds a capability (e.g. runtime route creation), the threat model must say who can use it and what stops abuse.

Anything security-relevant that exists in code but not in the design is an **undocumented attack surface**. Report it, and recommend either documenting it in the threat model or removing it.

### 4d. Decide which side is wrong

A divergence doesn't automatically mean the code is wrong. Judge which is right:
- If the code's behavior is safer or more correct, recommend updating the doc (lower severity: the risk is misleading readers).
- If the doc describes the intended, safer behavior, the code has a gap (severity by impact).
- If both are defensible, flag it as a decision to make, and say what each choice implies.

Also check the doc's own **known gaps / assumptions** list. A gap listed as known but actually fixed makes the doc stale (Low). A real gap the code has but the doc omits is more serious, because the docs then overstate the system's security.

### 4e. Severity for design findings

- **Critical**: a security or threat-model claim is false in the code (e.g. "tenants cannot modify routes" but the admin API accepts tenant tokens; "rejected requests never reach the upstream" but one path does). Also: a required design deliverable is missing.
- **Should Fix**: a functional divergence that changes behavior callers rely on (wrong status codes, different policy order, audit fields missing, quota semantics differ); a security claim that is implemented but untested; undocumented security-relevant surface.
- **Low**: the doc is stale or imprecise in ways that don't change behavior; a diagram is missing a failure branch the code handles correctly; the known-gaps list is out of date.
- **Nit**: naming or terminology mismatches between doc and code.

Report each design finding in the normal severity sections with a **[Design]** tag in the title, cite both the doc location (`[ARCHITECTURE.md:42](ARCHITECTURE.md#L42)`) and the code location, and include the full ledger in the report's Design conformance section (§8).

## 5. Verify against public documentation

Your memory of an API's behavior is a hypothesis, not evidence. Check public documentation whenever a finding (or a decision *not* to flag something) depends on:
- What a library/JDK method guarantees (thread safety, atomicity, null handling, exceptions thrown, defaults like timeouts or redirect following).
- Protocol rules (HTTP semantics, status codes, hop-by-hop headers, JWT claim validation, OAuth flows).
- Security guidance (OWASP cheat sheets, NIST, vendor advisories).
- Framework behavior that changed across versions — check the docs for the version in the build file.
- Tooling semantics (GitHub Actions syntax, Maven/Gradle plugin behavior).

How to look things up: use whatever web tools are available (WebFetch, WebSearch, or a web-fetch subagent). Prefer primary sources, in roughly this order:
1. Official API docs for the exact version (e.g. `docs.oracle.com/en/java/javase/<ver>/docs/api/`, `docs.spring.io`, `pkg.go.dev`, `docs.python.org`).
2. Specs and standards (`rfc-editor.org`, `datatracker.ietf.org` — e.g. RFC 9110 HTTP Semantics, RFC 7519 JWT, RFC 8725 JWT BCP, RFC 6585 for 429).
3. Security references (OWASP Cheat Sheet Series, CWE entries, GitHub/NVD advisories).
4. The library's own repository docs/source when official docs are thin.
Avoid relying on blog posts or Stack Overflow as the authority; they can point you to the right place but cite the primary source.

Cite the specific URL (with anchor/section when possible) in the finding. If a lookup fails or no web access exists, say "unverified — based on knowledge of <lib> <version>" so the reader knows the confidence level. Don't fabricate URLs or quote docs you haven't seen.

You don't need to look up things that are truly unambiguous (a plain null dereference, an obviously inverted condition). Spend lookups where being wrong would be costly or where the behavior is subtle.

## 6. Validate each finding before reporting

For each candidate issue, ask:
- **Is it real?** Re-read the code path end to end. Check whether a caller, filter, framework default, or config already handles it. If it depends on a library behavior, did you verify it (§5)?
- **Can it actually happen?** Describe the concrete input/state that triggers it. If you can't, it's either a Low/Nit hardening suggestion or not a finding.
- **What's the real impact?** That drives severity, not how interesting it is.
- **Is the fix right?** The proposed fix should compile in your head, fit the codebase's style, and not introduce a new problem.

Drop findings that don't survive. If uncertain but plausible, keep it, mark the confidence, and explain what would confirm it.

## 7. Assign severity

Severity is about consequence if shipped, not effort to fix.

- **Critical** — Will cause wrong results, data loss, security compromise, crashes, or outages under realistic conditions; or a required deliverable/spec requirement is missing or broken. Examples: auth bypass, policy check skippable via path encoding, race that corrupts rate-limit counts under load, upstream errors crash the server, tests don't compile, CI never runs tests, secrets committed.
- **Should Fix** — Real defect or significant risk that isn't immediately catastrophic: misbehaves under edge cases or load, resource leaks, missing timeouts, unbounded memory growth, misleading error responses, important untested paths, notable inefficiency on a hot path, misuse of an API that works today only by accident.
- **Low** — Minor bugs with small blast radius, defensive hardening, maintainability problems that will bite later, missing-but-nice tests, small inefficiencies, documentation gaps.
- **Nit** — Style, naming, formatting, comment wording, idiom preferences. Things a reasonable engineer could disagree with. Keep these brief.

When torn between two levels, pick the higher one only if you can state the concrete failure scenario; otherwise pick the lower one.

## 8. Report format

Use this structure. Within each severity, order by impact (most damaging first). Number findings continuously (C1, C2…, S1…, L1…, N1…) so the user can refer to them.

~~~markdown
# Code Review: <project/branch/PR>

**Scope:** <what was reviewed — files/commits/LOC>  ·  **Stack:** <language + versions, key libraries>
**Build/tests:** <what you ran and the outcome>

## Summary
<3–6 sentences: overall quality, the most important risks, and whether it's ready to ship/submit. Then a count line:>
Critical: N · Should Fix: N · Low: N · Nit: N

## Critical

### C1. <Short, specific title>
**Location:** [File.java:42](path/to/File.java#L42) (and others if relevant)
**Problem:** <What is wrong, concretely.>
**Impact / scenario:** <Exact input or state → what goes wrong. Note "Verified by reproduction" if you proved it.>
**Fix:**
```java
// minimal corrected code, or precise description of the change
```
**Reference:** <URL to primary doc/spec, with section> — <one line on what it says>

## Should Fix
### S1. ...

## Low
### L1. ...

## Nit
- **N1.** [File.java:10](path#L10) — <one line>. (Nits can be a compact list.)

## Requirements checklist   ← include only if a spec/brief exists
| Requirement | Status | Notes |
|---|---|---|
| ... | ✅ Met / ⚠️ Partial / ❌ Missing | finding IDs |

## Design conformance   ← always include; say "No design docs found" if none
**Docs checked:** <list of design docs and diagrams read>
| # | Claim (doc location) | Code location | Test | Status | Finding |
|---|---|---|---|---|---|
| D1 | "Auth runs before quota" ([ARCHITECTURE.md:30](ARCHITECTURE.md#L30)) | [GatewayFilter.java:55](...) | AuthOrderIT | ✅ Implemented | — |
| D2 | "Tenants cannot modify routes" ([THREAT_MODEL.md:12](...)) | [AdminController.java:20](...) | none | ❌ Diverges | C2 |
Statuses: ✅ Implemented · 🟡 Implemented, untested · ⚠️ Partial · ❌ Diverges · ❌ Missing

**Undocumented behavior found in code:** <bulleted list with locations and finding IDs, or "None found.">

## What's done well
<Brief, specific. Helps the reader know what not to change.>

## Limits of this review
<What you couldn't run, verify, or reach; unverified assumptions.>
~~~

Formatting notes:
- If a section is empty, write "None found." rather than omitting it — that tells the reader you looked.
- Use clickable `[file:line](path#Lline)` links relative to the workspace root.
- Keep each finding self-contained; the reader may jump straight to it.
- If the same issue appears in many places, report it once and list all locations.
- For very large reports, the Critical and Should Fix sections deserve full detail; Low and Nit can be terser.

## 9. After the report

End by offering next steps, e.g. "Want me to fix the Critical and Should Fix items?" If the user agrees, fix in severity order, run the tests after each group, and report what changed.

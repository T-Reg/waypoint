# Java / JVM Review Checklist

Use alongside the passes in SKILL.md. These are prompts for where to look, not an exhaustive list — and each item is something to **verify** in the actual code and, where behavior is subtle, in the docs for the JDK/library version in the build file.

Primary docs:
- JDK API: `https://docs.oracle.com/en/java/javase/<version>/docs/api/` (match the `maven.compiler.release` / `java.toolchain` version)
- JLS / JMM: `https://docs.oracle.com/javase/specs/` (Chapter 17 for memory model)
- Spring Boot / Framework: `https://docs.spring.io/spring-boot/` and `https://docs.spring.io/spring-framework/reference/`
- JUnit 5: `https://junit.org/junit5/docs/current/user-guide/`
- Mockito: `https://javadoc.io/doc/org.mockito/mockito-core/latest/`
- Maven: `https://maven.apache.org/`, Gradle: `https://docs.gradle.org/current/userguide/`

## Contents
1. Concurrency
2. Resources and I/O
3. Correctness pitfalls
4. Exceptions and error handling
5. HTTP clients and servers in Java
6. Spring Boot specifics
7. Security specifics
8. Performance
9. Tests
10. Build and CI
11. Readability / idiom

## 1. Concurrency
- `HashMap`/`ArrayList` shared across request threads → data races. Should be `ConcurrentHashMap`, immutable copies, or properly synchronized.
- Check-then-act on concurrent maps: `if (!map.containsKey(k)) map.put(k, v)` or `get` → modify → `put` is not atomic. Use `computeIfAbsent`, `compute`, `merge`. Note: `ConcurrentHashMap.compute*` functions must be short and must not modify the same map (see Javadoc — can deadlock or throw `IllegalStateException` for recursive updates).
- Counters: `int count++` / `long` fields shared across threads → use `AtomicLong`, `LongAdder` (high contention, sums only), or locks. `volatile` gives visibility, **not** atomicity of `++`.
- Rate limiter state that's read and updated in several steps (window start + count) needs a single atomic unit: a lock per key, `compute` over an immutable record, or an `AtomicReference` CAS loop.
- Copy-on-write route tables: an `AtomicReference<Map<...>>` / `volatile` immutable map swapped on update is a good pattern; mutating a map while readers iterate is not.
- `synchronized` on a boxed `Integer`/`String` literal or on `this` of a public object → shared/unpredictable lock. Prefer private final lock objects.
- `SimpleDateFormat`, `MessageDigest`, `Mac`, `Cipher`, `java.util.Random` (contention) — not thread-safe or shouldn't be shared; check how they're held. `Mac` instances per call or `ThreadLocal`. `DateTimeFormatter` is thread-safe.
- Executors: unbounded `newCachedThreadPool` under load, `newFixedThreadPool` with unbounded queue → memory growth; executors never shut down; `CompletableFuture.supplyAsync` without an executor uses the common pool (bad for blocking I/O).
- Virtual threads (Java 21+): pinning inside `synchronized` blocks doing blocking I/O (improved in JDK 24 — check version), `ThreadLocal` caches that assume few threads.
- Double-checked locking without `volatile`.

## 2. Resources and I/O
- Streams, readers, `HttpURLConnection` input/error streams, `ResultSet`/`Connection` closed via try-with-resources on all paths, including exceptions.
- `java.net.http.HttpClient`: should be created once and reused (it holds a connection pool). Before JDK 21 it isn't `AutoCloseable`. Defaults: **no request timeout** unless `HttpRequest.Builder.timeout()` is set; `connectTimeout` unset by default; redirects `NEVER` by default. Verify in Javadoc for the version used.
- Reading entire bodies into memory (`readAllBytes`, `BodyHandlers.ofString`) for proxied traffic → memory/DoS risk; check for size limits or streaming (`ofInputStream`, `BodyPublishers.ofInputStream`).
- `Files.lines`/`Files.list`/`Files.walk` return streams that must be closed.
- Charset: `new String(bytes)` / `getBytes()` without a charset uses the platform default (UTF-8 by default since JDK 18, JEP 400 — but be explicit, and check the target version).

## 3. Correctness pitfalls
- `==` on `String`, boxed `Integer` (cache only −128..127), `Long`, enums are fine.
- `equals` overridden without `hashCode` (or vice versa); mutable fields used in `hashCode` of map keys. Records generate both — good.
- `Optional.get()` without a check; `Optional` as field/parameter; returning `null` from a method typed `Optional`.
- Integer overflow in time math: `int` millis, `System.currentTimeMillis()` for measuring elapsed time (use `System.nanoTime()` for latency; it's monotonic). `Instant`/`Clock` for wall time; inject a `Clock` so time-dependent logic (quotas, token expiry) is testable.
- `String.split` with regex metacharacters, `split` dropping trailing empty strings.
- `startsWith` for path prefix matching: `/api` matches `/apiadmin`. Prefix match must respect segment boundaries; longest-prefix wins should be deterministic (not dependent on `HashMap` iteration order).
- URL/URI building by string concatenation → double slashes, lost query strings, unencoded characters. Use `URI.resolve` carefully (it treats a base without trailing slash differently — check Javadoc) or a builder.
- `BigDecimal` from `double`, `equals` vs `compareTo` on `BigDecimal`.
- Collections returned from getters that are mutable internal state.
- `switch` without `default`, fall-through.
- Locale-sensitive `toLowerCase()`/`toUpperCase()` on identifiers/headers → use `Locale.ROOT`.

## 4. Exceptions and error handling
- Swallowed exceptions (`catch (Exception e) {}`), catching `Throwable`/`Error`, logging and rethrowing (double logs), losing the cause (`new RuntimeException(e.getMessage())`).
- `InterruptedException` caught without restoring the interrupt flag (`Thread.currentThread().interrupt()`).
- Exceptions used for control flow on hot paths.
- Error responses leaking stack traces or internal hostnames to clients.
- Upstream failure (connect refused, timeout) mapped to a sensible status (502/504) rather than 500 or a hang.

## 5. HTTP clients and servers in Java
- Header names are case-insensitive (RFC 9110 §5.1) — lookups via a case-sensitive `Map<String,String>` are bugs. Multi-valued headers collapsed into one value.
- `com.sun.net.httpserver.HttpServer`: default executor is single-threaded unless `setExecutor` is called; must call `sendResponseHeaders` before writing body; `-1` vs `0` length semantics; exchanges must be closed.
- Restricted headers in `java.net.http.HttpClient` (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`) — setting them throws `IllegalArgumentException` unless `jdk.httpclient.allowRestrictedHeaders` is set. A proxy that copies all inbound headers will fail at runtime. Verify list in the `java.net.http` module docs.
- Servlet: reading request body twice, `getParameter` consuming body, async servlet not completed.
- See `http-services-security.md` for proxy/header/path rules.

## 6. Spring Boot specifics
- Field injection (`@Autowired` on fields) vs constructor injection — Nit/Low unless it hinders testing.
- Singleton beans holding per-request mutable state → race.
- `@Transactional` on private methods or self-invocation (proxy-based AOP doesn't apply) — check docs.
- `@Async`/`@Scheduled` without enabling annotations or with default executors.
- Spring Security filter order; `permitAll` matchers too broad; `requestMatchers` path matching differences (`PathPatternParser` vs `AntPathMatcher`, trailing slash matching was **removed as default in Spring Framework 6** — verify for version).
- WebFlux/Reactor: blocking calls (`.block()`, JDBC, `Thread.sleep`) on event-loop threads; `subscribe()` inside handlers losing backpressure/errors.
- Spring Cloud Gateway: if used, check filter ordering, `RouteLocator` refresh for dynamic routes, and whether built-in `RequestRateLimiter` needs Redis.
- Actuator endpoints exposed without auth (`management.endpoints.web.exposure.include=*`).
- Secrets in `application.yml` committed to the repo.

## 7. Security specifics
- Comparing secrets/signatures with `equals`/`Arrays.equals` → timing leak. Use `MessageDigest.isEqual` (constant-time since JDK 6u17).
- JWT libraries (jjwt, nimbus-jose-jwt, java-jwt/auth0): verify algorithm is pinned (no `alg: none`, no RS/HS confusion), signature verified (`parseSignedClaims`/`verify`, not just decode), `exp`/`nbf` checked with bounded clock skew, `iss`/`aud` validated. Check the specific library's docs for the version — APIs changed significantly (e.g. jjwt 0.12 `parser().verifyWith(...)`).
- HMAC keys: `SecretKeySpec` from a hardcoded string; keys shorter than the hash output; keys logged.
- `SecureRandom` for tokens/IDs, not `Random`/`Math.random`/`UUID.nameUUIDFromBytes`.
- Deserialization of untrusted data (`ObjectInputStream`, Jackson default typing `enableDefaultTyping`/`activateDefaultTyping` with broad validators).
- Jackson: `FAIL_ON_UNKNOWN_PROPERTIES` choices, unbounded input sizes (Jackson 2.15+ has `StreamReadConstraints` defaults — verify version).
- Log injection: user-controlled values (paths, headers) logged without escaping CR/LF in non-structured logs. Structured JSON logging mitigates.
- Dependencies with known CVEs — check `mvn dependency:tree` versions against GitHub advisories if time permits.

## 8. Performance
- Regex `Pattern.compile` inside hot loops/request handling — compile once as `static final`.
- String concatenation in loops; `String.format` in hot paths; logging with string concatenation instead of parameterized `log.debug("x={}", x)` (cost when level disabled).
- Creating `ObjectMapper`, `HttpClient`, `Mac`/`KeyFactory`, JWT parsers per request when they're reusable/thread-safe (`ObjectMapper` is thread-safe after configuration).
- Synchronizing the whole request path on one global lock (serializes all traffic).
- Linear scans over route tables per request — fine for small N; note if N could grow.
- Autoboxing in hot counters (`Map<String, Integer>` increments).

## 9. Tests
- JUnit 5 vs 4 mixed (`org.junit.Test` with Jupiter engine → tests silently don't run). Check that Surefire/Failsafe actually picks up tests (`*Test`/`*IT` naming, Failsafe plugin configured for integration tests).
- Integration tests: use an in-process stub upstream (WireMock, MockWebServer, `HttpServer` on port 0) rather than the internet. Random ports (`0` / `@SpringBootTest(webEnvironment = RANDOM_PORT)`).
- Time-dependent logic tested with `Thread.sleep` → slow and flaky; inject `Clock`.
- Assertions on status code only, not on the reason/body, the audit log entry, or that the upstream was **not** called for rejected requests.
- Concurrency claims tested with actual parallel requests (e.g. `ExecutorService` + `CountDownLatch`) when rate limiting correctness matters.
- Mockito: over-mocking that tests the mocks; `when(...)` stubs never used (strict stubs flag this).
- AssertJ/Hamcrest misuse: `assertThat(x)` without a terminal assertion.

## 10. Build and CI
- Java version consistent across `pom.xml`/`build.gradle`, Dockerfile, and CI (`actions/setup-java` `java-version`).
- Maven wrapper / Gradle wrapper committed and executable (`chmod +x mvnw`), wrapper JAR present or wrapper configured to download.
- Plugin and dependency versions pinned (no `LATEST`/`RELEASE`, no dynamic `+` in Gradle).
- GitHub Actions: workflow triggers (`on: push` and `pull_request`), `actions/checkout`/`setup-java` major versions current, dependency caching (`cache: maven`/`gradle`), the job actually runs `verify`/`check` (not just `compile`), and integration tests are included. Least-privilege `permissions:`. Verify syntax against `https://docs.github.com/en/actions`.
- Dockerfile: multi-stage build, non-root user, JRE (not JDK) runtime image, no secrets baked in, `HEALTHCHECK` or orchestrator probe.

## 11. Readability / idiom
- Records for immutable data carriers (Java 16+), sealed types for closed hierarchies (17+), `switch` expressions, `var` where it helps rather than hides types.
- God classes mixing HTTP handling, policy, and persistence → suggest separation (auth → policy → proxy → audit as distinct components is easier to test).
- Meaningful package structure; no `utils` grab-bag.
- Public API Javadoc on non-obvious behavior; comments explaining *why*.
- Consistent null strategy (annotations or Optional on returns).

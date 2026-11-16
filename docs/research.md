# Research & reading list

Each link is paired with what to take from it for our design.

## Open-source gateways & dynamic routing

The well-known open-source gateway is **Kong**. **Envoy**, **Traefik** and **Apache APISIX** are the other big ones.

- **Envoy: dynamic configuration (RDS/xDS)**
  - Links: [Architecture overview](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/operations/dynamic_configuration) · [Solo.io: building a control plane for Envoy](https://www.solo.io/blog/building-a-control-plane-for-envoy)
  - Route tables are swapped in whole without disturbing in-flight requests, which is our `AtomicReference` snapshot.
  - Config is eventually consistent across proxies.
  - A bad update is NACKed and the last-known-good config stays in place, which is our "validate before swap".
- **Kong**
  - Links: [Rust-based router deep dive](https://konghq.com/blog/engineering/rust-based-router-kong-gateway) · [Expressions router docs](https://developer.konghq.com/gateway/routing/expressions/) · [Incremental config sync](https://developer.konghq.com/gateway/incremental-config-sync/)
  - Regex routes are expensive and prefix routes are cheap.
  - Rebuilding the whole router on every change got slow at around 10k routes, so they moved to rebuilding only when routes are affected, and then to incremental sync.
  - Our scale doesn't need this, but it's worth mentioning as a scaling note.
- **Traefik**
  - Link: [Configuration / providers overview](https://doc.traefik.io/traefik/v3.4/providers/overview/)
  - Providers push config, and a watcher debounces and merges it into one snapshot.
  - `providersThrottleDuration` prevents reload storms.
  - Static config (needs a restart) is kept separate from dynamic config (hot reload).
- **Cloudflare Pingora**
  - Links: [How we built Pingora](https://blog.cloudflare.com/how-we-built-pingora-the-proxy-that-connects-cloudflare-to-the-internet/) · [Open sourcing Pingora](https://blog.cloudflare.com/pingora-open-source)
  - Request-phase callbacks work like a filter chain.
  - Why the threading/connection model matters for a proxy: connection reuse across workers.
- **Netflix Zuul 2**
  - Links: [How it works 2.0](https://github.com/Netflix/zuul/wiki/How-It-Works-2.0) · [How we use Zuul at Netflix](https://github.com/Netflix/zuul/wiki/How-We-Use-Zuul-At-Netflix)
  - An inbound → endpoint → outbound filter chain, which matches our pipeline.
  - Canary traffic is shifted gradually through dynamic config.
  - Never block on the event loop. Our virtual-thread model avoids this class of problem.
- **Spring Cloud Gateway** (reference only): predicates and filters is the idiomatic Java take on the same pattern.

## Rate limiting
- [Stripe: Scaling your API with rate limiters](https://stripe.com/blog/rate-limiters)
  - A token bucket per user, allowing short bursts.
  - Four limiter types, from request rate limiting up to load shedding.

## Tokens
- [RFC 7519: JWT](https://datatracker.ietf.org/doc/html/rfc7519)
- [RFC 8725: JWT Best Current Practices](https://datatracker.ietf.org/doc/html/rfc8725): pin algorithms and validate `iss`/`aud`/`exp`.
- [OWASP JWT for Java cheat sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
- [Aembit: API keys vs JWTs](https://aembit.io/blog/api-keys-vs-jwts/)
  - API keys: instant revocation, but a lookup on every request.
  - JWTs: verified locally, but revocation is hard.

## Identity propagation & the confused deputy
- **Netflix: "Edge Authentication and Token-Agnostic Identity Propagation"** (Netflix Tech Blog, 2021; [summary via OWASP's identity-propagation update](https://www.innoq.com/en/blog/2025/06/owasp-microservice-security-cheat-sheet-update-identity-propagation/)).
  - Raw tokens end at the edge.
  - A signed "Passport" identity object travels inward instead.
- [OWASP Microservices Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Microservices_Security_Cheat_Sheet.html): the identity propagation patterns, and why plain headers aren't enough.
- [RFC 8693: OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693): the `act` claim for "service A acting on behalf of the customer", which solves the second hop.
- [OWASP API1:2023 Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/): why scopes aren't enough, and why object checks belong in the service that owns the data.

## Threat modeling
- [OWASP Threat Modeling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Threat_Modeling_Cheat_Sheet.html) (start here)
- [Shostack: The Four Question Framework (PDF)](https://shostack.org/files/papers/The_Four_Question_Framework.pdf). The four questions are:
  1. What are we working on?
  2. What can go wrong?
  3. What are we going to do about it?
  4. Did we do a good job?
- [OWASP Threat Modeling Project](https://owasp.org/www-project-threat-modeling)
- [Threat Modeling Manifesto](https://www.threatmodelingmanifesto.org/)

**What a good threat model contains:**
- A data-flow diagram with trust boundaries.
- The assets worth protecting.
- Threats enumerated for each element (STRIDE: Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege).
- A mitigation mapped to each threat.
- Accepted risks stated explicitly.
- It is kept current as the design changes.

## Privacy & encryption
- [RFC 9849: TLS Encrypted Client Hello](https://www.rfc-editor.org/rfc/rfc9849.html): hides SNI from network observers.
- [RFC 9180: HPKE](https://www.rfc-editor.org/rfc/rfc9180.html): public-key encryption of payloads, for gateway-blind bodies.
- [RFC 9458: Oblivious HTTP](https://www.rfc-editor.org/rfc/rfc9458.html): separates *who* (the relay sees the IP) from *what* (the gateway sees the request).
- [RFC 9576: Privacy Pass architecture](https://www.rfc-editor.org/rfc/rfc9576.html): anonymous authorization tokens.
- [RFC 9110 §7.6.1: hop-by-hop headers](https://www.rfc-editor.org/rfc/rfc9110#section-7.6.1)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)

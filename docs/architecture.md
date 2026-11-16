# Architecture: request flow

```mermaid
flowchart TD
    C[Client] -->|HTTPS| TLS[TLS terminator / load balancer<br/><i>external, not built by us</i>]
    TLS -->|plain HTTP + X-Forwarded-For| RID[Public listener: assign request ID, start timer<br/>client IP from XFF only if sender is a trusted proxy]
    RID --> READY{Snapshot loaded?}
    READY -->|no, still starting| R503b[503 gateway.not_ready]
    READY --> SIZE{Body within size limit?}
    SIZE -->|no| R413[413 request.too_large]
    SIZE --> NORM{Normalize path<br/>decode once; reject ; % \ .. encoded /}
    NORM -->|ambiguous| R400[400 request.bad_path]
    NORM --> AUTH{Verify JWT<br/>Ed25519, typ, iss, aud, exp, nbf, tid}
    AUTH -->|missing / invalid / expired| R401[401 + WWW-Authenticate]
    AUTH -->|unknown or disabled tenant| R401
    AUTH --> ALLOW{Path in tenant allowlist?}
    ALLOW -->|no| R403a[403 policy.path_denied]
    ALLOW --> ROUTE{Longest segment-prefix<br/>match in route snapshot}
    ROUTE -->|no match| R404[404 route.not_found]
    ROUTE -->|entry invalid| R503[503 route.unavailable]
    ROUTE --> METHOD{Route configured for this HTTP method?}
    METHOD -->|no| R405[405 policy.method_denied + Allow]
    METHOD --> SCOPE{Token has the route's required scopes<br/>for this method?}
    SCOPE -->|no| R403b[403 policy.scope_missing]
    SCOPE --> QUOTA{Fewer than RPM admissions in last 60 s?<br/>sliding window shared in Valkey; local RPM ÷ instances if down}
    QUOTA -.->|EVALSHA sliding window| VK[(Valkey<br/>rl:tid sorted sets)]
    QUOTA -->|no| R429[429 + Retry-After]
    QUOTA --> P1
    subgraph PROXY [Prepare and forward request]
        direction TB
        P1[1. Remove hop-by-hop headers<br/>Connection + headers it names, Keep-Alive, Transfer-Encoding, Upgrade, TE, Trailer, Proxy-*]
        P2[2. Remove Authorization, client X-Gateway-*,<br/>Forwarded, X-Real-IP, X-Forwarded-*]
        P3[3. Mint signed identity token, aud = this upstream, 60s TTL<br/>add X-Gateway-Identity, X-Request-Id, rebuilt X-Forwarded-*]
        P4[4. Re-resolve upstream host and re-check IPs, SSRF<br/>stream canonical re-encoded path + body, size-capped]
        P1 --> P2 --> P3 --> P4
    end
    PROXY -->|connect error / blocked IP / body drops before commit| R502[502 upstream.unavailable]
    PROXY -->|chunked body over limit| R413
    PROXY -->|timeout: headers, or body idle before commit| R504[504 upstream.timeout]
    PROXY -->|response| RESP[Return upstream status, headers, body<br/>body idle timeout = route timeoutMs per read]
    RESP -->|body stalls or drops after commit| ABORT[Close client connection, no terminating chunk]
    PROXY -->|unexpected error| R500[500 internal]

    R503b & R413 & R400 & R401 & R403a & R404 & R503 & R405 & R403b & R429 & R502 & R504 & R500 & RESP & ABORT --> AUDIT[(Audit log: one JSON line, always written in finally<br/>path minimized by default: prefix + keyed HMAC)]

    ADMIN[Admin client] -->|admin listener + admin JWT<br/>own key, aud=gateway-admin, admin:* scopes| ADM{Admin API}
    ADM -->|bad token / missing scope| RA[401 / 403 admin.scope_missing]
    ADM -->|invalid / missing If-Match / version conflict / DB down| RB[400 / 428 / 409 / 503]
    RA & RB --> AUDIT
    ADM -->|tx: write + bump revision + audit + NOTIFY| DB[(PostgreSQL<br/>routes, tenants, config_revision,<br/>config_audit, gateway_keys)]
    ADM -->|POST /admin/keys/rotate| KEYS[Key rotation broadcast]
    DB -.->|LISTEN config_changed, plus poll fallback| SWAP[Validate per entry, atomic snapshot swap]
    SWAP -.-> ROUTE

    UP[Upstream service] -.->|out-of-band, cached; refetch on unknown kid<br/>fixed URL over TLS| JWKS[Management listener<br/>GET /.well-known/jwks.json, public keys only]
    JWKS -.->|last-known-good key set, refreshed from| DB
    KEYS -.-> JWKS

    style TLS stroke-dasharray: 5 5
```

Rejected requests never reach the upstream. The audit entry is written for every outcome.

The request path reads only the in-memory snapshot and never queries the database, so a DB outage doesn't stop traffic. Admin writes go to PostgreSQL and reach every instance via `NOTIFY`, with a poll as fallback. See [multi-instance.md](multi-instance.md).

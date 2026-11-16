# Confidentiality & Traffic Privacy

Part of the [design](design.md). Who can see what about a request, what the gateway does about it, and what is deferred.

**Who sees what for `GET /cities/chicago/weather`:**

| Party | Sees over TLS | Mitigation |
|---|---|---|
| Network observer | IP, SNI hostname, timing, sizes; not path/headers/body | TLS. [ECH (RFC 9849)](https://www.rfc-editor.org/rfc/rfc9849.html) hides SNI at the TLS terminator. Optional per-route response padding against size fingerprinting. |
| Gateway / its logs | Everything (TLS ends here) | **Log minimization by default:** `route_id` + matched prefix + keyed HMAC of the full path instead of the raw path (raw path opt-in per tenant). Query never logged. Retention limits. |
| Upstream | Everything | Out of scope; it's the service answering the request. |

**What we build:** a TLS baseline (assumed in front of the gateway), audit-log minimization, and optionally response padding.

**Tenant-side end-to-end encryption works without any gateway feature:**
- A tenant can keep sensitive values out of paths and query strings, e.g. `POST /weather {"city":"chicago"}`.
- It can then encrypt the body to its upstream's public key ([HPKE, RFC 9180](https://www.rfc-editor.org/rfc/rfc9180.html), or JWE).
- Because the gateway forwards bodies untouched, it only ever sees ciphertext.
- Integration test: an opaque binary body round-trips unchanged and never appears in the audit log.

**Designed but deferred:**
- **Gateway-blind route mode:** the gateway enforces policy only at the coarse prefix and treats the body as opaque. Distributing upstream keys is the open problem.
- **Oblivious HTTP ([RFC 9458](https://www.rfc-editor.org/rfc/rfc9458.html)):** a relay sees the client IP but not the request, and the gateway sees the request but not the IP.
- **Privacy Pass ([RFC 9576](https://www.rfc-editor.org/rfc/rfc9576.html)):** anonymous authorization. It conflicts with per-tenant identity and quotas.
- **Why deferred:** path-level policy and auditing require the gateway to see the path, and time is limited. These are possible future improvements for the current gap that the gateway can see request paths.

# Q3: Multi-Tenant API Gateway Service

## Coding Interview: Multi-Tenant API Gateway REST Service

## Mandatory Environment Rules

To ensure your work is saved and evaluated correctly, you must adhere to the following operational constraints:

- **Stay in the Workspace:** All development must occur inside the `/workspaces` directory within your IDE.
- **No Host Saving:** Do not save code or assets to the host machine's Desktop or Downloads folder. These areas are not bridged to the container; data saved there will be permanently lost when the session ends.
- **Final Submission:** You must push your code and architecture diagram to a personal GitHub repository before the timer expires.
- **Session Cleanup:** Sign out of all personal accounts (GitHub, Cursor, Browser) before handing back the workstation.

## Logistics and Tooling

| Item | Details |
|------|---------|
| **Time Limit** | 3 Hours |
| **Supported Languages** | Go, Python, Java, or TypeScript / Node.js. (Standard extensions for YAML, Python, Go, and Java are highly recommended). |
| **IDE Environment** | VS Code or Cursor, bridged directly to a Ubuntu 24 Dockerized container. |
| **AI Assistance** | GitHub Copilot, Claude Code, and Cursor are permitted. You are encouraged to use Chrome, documentation, and any AI tools to assist your development. You must sign in using your personal accounts and remain fully responsible for the review, architecture, and correctness of all generated outputs. |
| **Permissions** | You have passwordless sudo access to install additional dependencies via apt or brew. |
| **Pre-Installed Utilities** | GitHub CLI (`gh`), `doctl`, `s3cmd`, `jq`, `yq`, `neovim`, and core image processing libraries. |

## Project Objective

**Summary:** Build a production-ready reverse proxy / API gateway that authenticates callers, routes requests to configurable upstream services, enforces per-tenant policies (rate limits, allowed paths, auth scopes), and records an auditable request log.

## Functional Expectations

At a minimum, your service should demonstrate:

- **Authentication & Identity:** Validate inbound requests using a token scheme of your choice (e.g. JWT, HMAC-signed API key). Reject unauthenticated requests with appropriate status codes. Associate every request with a resolved tenant identity.
- **Dynamic Routing:** Maintain a route table mapping path prefixes to upstream URLs. Allow routes to be added, updated, and removed at runtime without restart. Proxy matched requests to the configured upstream and return the upstream response faithfully.
- **Policy Enforcement:** Apply per-tenant policies before proxying: path allowlist, per-minute request quota, and required scope checks. Policy violations must be rejected with descriptive errors; they must not reach the upstream.
- **Audit Log:** Write a structured log entry for every inbound request capturing tenant ID, path, upstream target, policy decision, upstream response code, and latency.

## Engineering Expectations

Your solution should reflect what you believe constitutes a production-ready service. We require:

- **Architecture Flow Diagram:** Include a diagram showing the request path from inbound connection through auth, policy checks, proxy, and audit — including failure branches.
- **Security Posture:** Document your threat model: what an authenticated-but-malicious tenant can and cannot do with your design.
- **Testing:** Unit tests for auth and policy logic. Integration tests that exercise the full proxy path including policy rejection cases.
- **CI/CD:** A functional GitHub Actions pipeline that runs your full test suite on push.
- **Documentation:** README covering setup, token issuance, and a section on 'security assumptions and known gaps'.

## Extensions & Next Steps

If time permits, you are encouraged to expand on your solution:

- **Deployment:** Deploy to DigitalOcean; point the gateway at a real upstream (e.g. a public API or a second app you deploy).
- **Circuit Breaker:** Implement upstream circuit-breaking with configurable failure thresholds and half-open probe logic.
- **Canary Routing:** Support traffic-splitting to route a percentage of a tenant's requests to an alternate upstream.

---

**Good luck. We look forward to reviewing your solution.**

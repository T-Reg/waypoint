# Deploying to DigitalOcean

Part of the [design](design.md). This guide runs Waypoint on App Platform with managed PostgreSQL and Valkey. Infrastructure is provisioned by hand with `doctl` and the control panel; the repo holds only the app spec template (`.do/app.yaml`).

## Architecture

```
Internet ──HTTPS──► App Platform edge (TLS, load balancer, health checks, rolling deploys)
                          │ HTTP :8080 only
                          ▼
              gateway service × 2  (:8080 public; :8081 admin and :8082 management are internal_ports)
                │            │                 │
     private network     HTTPS            TLS, trusted sources = this app (step 8)
                ▼            ▼                 ▼
     upstream service   public API      Managed PostgreSQL 16 (config store)
     (WireMock, internal) (e.g. httpbin) Managed Valkey 8 (shared quotas)

  gateway stdout (app + audit logs) ──App Platform log forwarding──► Managed OpenSearch (waypoint-logs)
```

| Local stack | On DigitalOcean |
|---|---|
| HAProxy | App Platform load balancer. Health check: port 8082, `/actuator/health/readiness` |
| Readiness wait before SIGTERM (`GATEWAY_SHUTDOWN_DELAY`) | `termination.drain_seconds` (15s) removes the instance from the LB *before* SIGTERM, so the delay is only 5s. `grace_period_seconds` (60s) covers it plus the 30s drain |
| `init-roles.sh` | Users created with `doctl`; `doadmin` runs the grants (step 3) |
| Valkey ACL user limited to `rl:*` | Managed Valkey has no ACL users ([limits](https://docs.digitalocean.com/products/databases/valkey/details/limits/)). The gateway uses `default` on a cluster dedicated to it |
| Seed file | None. Routes and tenants are created through the admin API (step 6) |
| Admin port on localhost | Internal port only; reached from the App Platform console |

Approximate cost: two `apps-s-1vcpu-1gb` gateways (about $24/mo), one small upstream (about $5/mo), 1 GB PostgreSQL and Valkey clusters (about $15/mo each), and a 2 GB OpenSearch cluster for logs. Check current pricing, and tear everything down when you're finished (step 8).

## 0. Prerequisites

```bash
brew install doctl libpq jq yq && brew link --force libpq
doctl auth init --context waypoint && doctl auth switch --context waypoint
doctl account get
setopt interactivecomments      # zsh: treat "# ..." in pasted commands as comments
```

Use one region throughout. App Platform regions map to a single datacenter ([table](https://docs.digitalocean.com/products/app-platform/how-to/enable-vpc/)). This guide uses `sfo` (app) and `sfo3` (databases).

## 1. Production keys and secrets

Keep them outside the repo, separate from `dev-keys/`:

```bash
mkdir -m 700 ~/waypoint-prod-keys && cd ~/waypoint-prod-keys
<repo>/scripts/token-cli keygen --out-dir ~/waypoint-prod-keys --name issuer
<repo>/scripts/token-cli keygen --out-dir ~/waypoint-prod-keys --name admin
(umask 077; openssl rand -base64 32 > ~/waypoint-prod-keys/audit-hmac.key)
```

Only the two public JWKs and the HMAC key are uploaded. The private keys stay on the operator's machine.

## 2. Managed PostgreSQL and Valkey

```bash
doctl databases create waypoint-pg     --engine pg     --version 16 --region sfo3 --size db-s-1vcpu-1gb --num-nodes 1
doctl databases create waypoint-valkey --engine valkey --version 8  --region sfo3 --size db-s-1vcpu-1gb --num-nodes 1
doctl databases list            # wait for "online"
PG_ID=<id>; VK_ID=<id>
doctl databases db create   $PG_ID waypoint
doctl databases user create $PG_ID waypoint_migrator
doctl databases user create $PG_ID waypoint_app
```

`doctl` masks passwords (`********`). Copy them from the control panel instead: **Databases → cluster → Overview → Connection Details → show**, and **Users & Databases → show** for each user.

### Log cluster (Managed OpenSearch)

The app spec forwards the gateway's runtime logs, audit entries included, to this cluster ([log forwarding](https://docs.digitalocean.com/products/app-platform/how-to/forward-logs/)). Create it before the app:

```bash
doctl databases create waypoint-logs --engine opensearch --version 2.19 --region sfo3 --size db-s-1vcpu-2gb --num-nodes 1
LOG_ID=<id>
doctl databases user reset $LOG_ID doadmin    # the create output prints the password in the URI
```

App Platform can't forward to an OpenSearch cluster that has trusted sources enabled, so leave this cluster's trusted sources **empty**. It is then reachable from the internet, protected by TLS and the `doadmin` password. The forwarder authenticates through `cluster_name`, so it needs no password in the spec. Only runtime logs are forwarded, and delivery is best-effort.

## 3. PostgreSQL roles

Connect as `doadmin` (host from Connection Details; paste the password when prompted):

```bash
psql "host=<pg-host> port=25060 user=doadmin dbname=waypoint sslmode=require"
```
```sql
REVOKE ALL ON DATABASE waypoint FROM PUBLIC;
GRANT CONNECT ON DATABASE waypoint TO waypoint_app, waypoint_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO waypoint_migrator;   -- Flyway creates and owns the tables
GRANT USAGE ON SCHEMA public TO waypoint_app;                -- the V2 migration grants DML
```

`waypoint_app` must exist before the first boot, because V2's grants run only once. If it's missing, the gateway refuses to migrate, stays not-ready and logs `App role 'waypoint_app' does not exist` (tested by R22).

Save the connection values for step 5. Copy each password from the panel first, then run its line:

```bash
K=~/waypoint-prod-keys
(umask 077; pbpaste > $K/pg-migrator.pw)     # waypoint_migrator password
(umask 077; pbpaste > $K/pg-app.pw)          # waypoint_app password
read VK_HOST VK_PORT <<< "$(doctl databases connection $VK_ID --format Host,Port --no-header)"
(umask 077; printf 'rediss://default:%s@%s:%s' "$(pbpaste)" "$VK_HOST" "$VK_PORT" > $K/valkey.url)   # Valkey password
doctl databases get-ca $PG_ID --format Certificate --no-header > $K/pg-ca.crt   # already PEM; don't base64-decode
openssl x509 -in $K/pg-ca.crt -noout -subject  # "... Project CA"
```

Before deploying, check the CA file and password with the same `verify-full` mode the gateway uses. This catches a bad CA file or password in seconds instead of after a failed deploy:

```bash
PGPASSWORD="$(cat $K/pg-app.pw)" psql \
  "host=<pg host> port=25060 user=waypoint_app dbname=waypoint sslmode=verify-full sslrootcert=$K/pg-ca.crt" -c 'select current_user'
```

## 4. Authorize GitHub

In the control panel, go to **Apps → Create App → GitHub** and install the DigitalOcean GitHub app for **only** the `waypoint` repo. Then cancel the wizard. `doctl` creates the app in step 5.

## 5. Create the app

Fill a git-ignored copy of the spec:

```bash
cp .do/app.yaml .do/app.local.yaml
K=~/waypoint-prod-keys
export PG_HOST='<pg host>' \
       PG_CA="$(cat $K/pg-ca.crt)" APP_PW="$(cat $K/pg-app.pw)" MIGRATOR_PW="$(cat $K/pg-migrator.pw)" \
       VK_URL="$(cat $K/valkey.url)" HMAC="$(cat $K/audit-hmac.key)" \
       ISSUER_JWK="$(jq -c . $K/issuer.public.jwk.json)" ADMIN_JWK="$(jq -c . $K/admin.public.jwk.json)"
yq -i '
  (.services[0].envs[] | select(.key == "DATABASE_URL")).value |= sub("REPLACE_ME_pg_host", strenv(PG_HOST)) |
  (.services[0].envs[] | select(.key == "DATABASE_CA_CERT")).value = strenv(PG_CA) |
  (.services[0].envs[] | select(.key == "DATABASE_PASSWORD")).value = strenv(APP_PW) |
  (.services[0].envs[] | select(.key == "GATEWAY_STORE_MIGRATION_PASSWORD")).value = strenv(MIGRATOR_PW) |
  (.services[0].envs[] | select(.key == "VALKEY_URL")).value = strenv(VK_URL) |
  (.services[0].envs[] | select(.key == "GATEWAY_AUDIT_HMAC_KEY")).value = strenv(HMAC) |
  (.services[0].envs[] | select(.key == "GATEWAY_CUSTOMER_PUBLIC_JWK")).value = strenv(ISSUER_JWK) |
  (.services[0].envs[] | select(.key == "GATEWAY_ADMIN_PUBLIC_JWK")).value = strenv(ADMIN_JWK)
' .do/app.local.yaml
unset APP_PW MIGRATOR_PW VK_URL HMAC
grep -v '^#' .do/app.local.yaml | grep -c REPLACE_ME   # expect 0
yq '.services[0].envs[] | select(.value == "" or .value == null) | .key' .do/app.local.yaml   # expect no output (a missing file leaves a value empty)

doctl apps spec validate .do/app.local.yaml > /dev/null && echo "spec valid"
```

`spec validate` prints the spec back with every secret filled in, so its output goes to `/dev/null`. Don't share it.

```bash
doctl apps create --spec .do/app.local.yaml && rm .do/app.local.yaml
```

Once the app exists, DigitalOcean stores the secrets encrypted (`doctl apps spec get` returns them as `EV[...]`).

How the connections are set up:
- `DATABASE_URL` uses `sslmode=verify-full` with the JDBC driver's `SingleCertValidatingFactory`, which reads the cluster CA from `DATABASE_CA_CERT`, so no certificate file is needed.
- `VALKEY_URL` is `rediss://` with a Let's Encrypt certificate that the JVM already trusts.
- The spec has no `databases:` block. App Platform counts each attached cluster as a new database against the account's database limit, even when the cluster already exists. So the values are filled in directly, and trusted sources are added by hand in step 8.

Follow the build and deploy:

```bash
APP_ID=$(doctl apps list --format ID,Spec.Name --no-header | awk '$2 == "waypoint" {print $1}')
doctl apps logs $APP_ID gateway --type build --follow
doctl apps logs $APP_ID gateway --type run --follow     # expect "Gateway ready at config revision 0"
```

If the deploy fails health checks, the run log shows why. `Startup not complete, retrying` lines carry the database or Valkey error.

## 6. Configure routes and tenants

The admin API isn't exposed publicly. Issue a short-lived admin token locally:

```bash
./scripts/token-cli issue-admin --key ~/waypoint-prod-keys/admin.private.jwk.json \
    --sub <your-name> --scope "admin:routes admin:tenants" --ttl 10m
```

Open a shell in a gateway container with `doctl apps console $APP_ID gateway` (or the app's **Console** tab), then run:

```bash
export ADMIN='<paste token>'
admin() { curl -s -X PUT -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' "localhost:8081/admin/$1" -d "$2"; echo; }

admin routes/weather '{"pathPrefix":"/weather","upstreams":[{"url":"http://upstream:8080"}],
  "requiredScopes":{"GET":["weather:read"]},"audience":"weather-svc","allowPrivateNetwork":true}'
admin routes/echo '{"pathPrefix":"/echo","upstreams":[{"url":"https://httpbin.org"}],
  "stripPrefix":true,"requiredScopes":{"*":["echo:read"]}}'
admin tenants/acme   '{"allowedPathPrefixes":["/weather","/echo"],"requestsPerMinute":600}'
admin tenants/globex '{"allowedPathPrefixes":["/weather"],"requestsPerMinute":60}'

curl -s localhost:8082/internal/status; echo           # revision 4 on this instance
```

The changes reach the other instance through `LISTEN/NOTIFY`. `/weather` goes to the internal WireMock upstream, which returns 400 unless `X-Gateway-Identity` is present and `Authorization` is absent. `/echo` goes to a public API that echoes the headers it receives.

## 7. Smoke test

```bash
APP_URL=https://$(doctl apps get $APP_ID --format DefaultIngress --no-header | sed 's#https://##')
issue() { ./scripts/token-cli issue --key ~/waypoint-prod-keys/issuer.private.jwk.json --tenant "$1" --sub demo --scope "$2"; }
ACME=$(issue acme "weather:read echo:read"); GLOBEX=$(issue globex "weather:read")

curl -si $APP_URL/weather/today | head -1                                   # 401: no token
curl -s  -H "Authorization: Bearer $ACME" $APP_URL/weather/today            # {"upstream":"ok"}
curl -s  -H "Authorization: Bearer $ACME" $APP_URL/echo/headers             # X-Gateway-Identity present, no Authorization
curl -si -H "Authorization: Bearer $GLOBEX" $APP_URL/echo/headers | head -1 # 403: path not allowed for globex
curl -si -H "Authorization: Bearer $(issue acme weather:read)" $APP_URL/echo/headers | head -1   # 403: scope missing
for i in $(seq 70); do curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $GLOBEX" $APP_URL/weather/x; done | sort | uniq -c   # 60 × 200, 10 × 429
doctl apps logs $APP_ID gateway --type run | grep '"tenant_id"' | tail -3   # audit entries
```

Check that audit entries reached OpenSearch: open **Databases → waypoint-logs → OpenSearch Dashboards**, create an index pattern for `waypoint-logs*`, and search for `tenant_id`.

Zero-downtime deploy: keep a request loop running against `$APP_URL/weather/x` while running `doctl apps create-deployment $APP_ID`. The drain, readiness and graceful shutdown should produce no errors.

## 8. Hardening and teardown

- **Trusted sources:** on the PostgreSQL and Valkey clusters (not `waypoint-logs`, see step 2), go to **Settings → Trusted sources → Edit** and add the `waypoint` app. Until you do, any IP can try to connect. Add your own IP only temporarily, when you need `psql`.
- **Valkey eviction policy:** if the cluster offers one, choose `noeviction`. Under memory pressure the limiter then degrades to local quotas instead of silently forgetting a tenant's window.
- **Alerts:** add app alerts (CPU, memory, restart count) and database alerts. Logs are already forwarded to `waypoint-logs` (step 2).
- **Deploys:** `deploy_on_push` is off. Run `doctl apps create-deployment $APP_ID` after CI is green on `main`.
- **Optional:** a custom domain (**Settings → Domains**), and VPC egress so the app reaches the databases on private hostnames.

### Changing the running app

Edit the deployed spec rather than `.do/app.yaml`, so the secrets stay encrypted (`EV[...]`) and don't need filling in again:

```bash
(umask 077; doctl apps spec get $APP_ID > ~/waypoint-prod-keys/live-spec.yaml)
yq -i '<your change>' ~/waypoint-prod-keys/live-spec.yaml
doctl apps update $APP_ID --spec ~/waypoint-prod-keys/live-spec.yaml   # triggers a rolling deploy
```

Skip `doctl apps spec validate` here. It checks the spec as a new app and rejects encrypted secrets (`secret env value must not be encrypted before app is created`). `doctl apps update` validates the spec itself and accepts them.

Teardown:

```bash
doctl apps delete $APP_ID
doctl databases delete $PG_ID
doctl databases delete $VK_ID
doctl databases delete $LOG_ID
```

## Known gaps on this platform

- **Valkey `default` user:** leaked credentials give full access to the cluster. Mitigations: a cluster dedicated to the gateway, TLS and trusted sources.
- **Client IP:** App Platform sends the client IP in `do-connecting-ip` and the ingress IP in `X-Forwarded-For` ([DO support](https://docs.digitalocean.com/support/where-can-i-find-the-client-ip-address-of-a-request-connecting-to-my-app)). The gateway reads only `X-Forwarded-For`, so the audit `client_ip` is the ingress address.
- **Log cluster exposure:** log forwarding requires trusted sources to be off on `waypoint-logs`, so it is reachable from the internet behind TLS and the `doadmin` password. A third-party destination (Better Stack, Papertrail) avoids this.
- **Internal ports:** any component of the app can reach the admin and management ports. The admin API still requires an admin JWT.

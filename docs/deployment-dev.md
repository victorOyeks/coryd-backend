# CoRyd — Dev Deployment Runbook

Deploys CoRyd's 5 services **plus an `api-gateway`** onto the **same shared
VPS** that already runs marketrun-ng, hospio360, and his-BE-staging. No new
server is provisioned — CoRyd gets its own subdomain, its own Postgres
database, and its own systemd units, but **reuses marketrun-ng's existing
Kafka and Redis** rather than running a second copy of each.

Because Kafka/Redis are shared with an unrelated product, isolation comes
from naming, not separate infrastructure:
- Every CoRyd Kafka topic is prefixed `coryd-` (e.g. `coryd-booking-confirmed`), and every consumer group ID is prefixed `coryd-<service-name>` (e.g. `coryd-identity-service`) — both baked into the code already. Without this, CoRyd's `identity-service` consumer group would collide with marketrun-ng's own `identity-service` consumer group on the same broker and the two unrelated apps would fight over each other's partitions.
- `trip-service` (the only service that touches Redis, for live driver location) uses logical DB index `1`, leaving marketrun-ng's default DB `0` untouched.

Domain: `coryd-api-dev.marketrunn.com` → Nginx → `api-gateway` (:9100) → the 5 services (:9101-9105), mirroring marketrun-ng's own gateway pattern. This also gives a single unified Swagger UI instead of 5 separate ones.

**Everything after Phase 6 is CI-driven** — GitHub Actions builds, ships, and starts all 6 (5 services + gateway) on the first push to `develop`, and every push after that. Phases 1-6 are one-time setup that only you can do (DNS/GitHub/VPS access), since I have none of those from here.

---

## Phase 1 — DNS (Cloudflare)

1. Log into the Cloudflare dashboard for the `marketrunn.com` zone.
2. Go to **DNS → Records → Add record**.
3. Type: `A`. Name: `coryd-api-dev`. IPv4 address: the VPS's public IP (same one `api-dev.marketrunn.com` already points to — check that record for the value).
4. Proxy status: **DNS only** (grey cloud), not proxied — Certbot needs to validate directly against the origin. You can switch to proxied (orange cloud) after the certificate is issued, if you want Cloudflare's edge in front.
5. Save. Propagation is usually near-instant on Cloudflare; confirm with `dig coryd-api-dev.marketrunn.com` from your machine before moving on.

---

## Phase 2 — VPS: directory layout + Postgres

SSH in as `devoyeks` (or whichever user has sudo on this box):

```bash
ssh devoyeks@<vps-ip>

# Confirm CoRyd's app ports are actually free before relying on them -
# marketrun uses 9000-9007, but hospio360/his-BE-staging ports aren't known
# from here.
sudo ss -tlnp | grep -E ':(9100|9101|9102|9103|9104|9105) '
# ^ should print nothing. If it prints something, pick different ports and
# update application.yml (6 modules) and the nginx site file accordingly.

# App directory - one subfolder per service (including the gateway),
# matching marketrun's layout
sudo mkdir -p /opt/coryd-dev/{api-gateway,identity-service,profile-service,trip-service,payment-service,notification-service,incoming}
sudo chown -R devoyeks:devoyeks /opt/coryd-dev

# Postgres already runs on this box (marketrun/his-BE use it) - no new
# install. Just add an isolated user + database for CoRyd on the existing
# instance; Liquibase creates the 5 schemas inside it on first boot.
sudo -u postgres psql -c "CREATE USER coryd_user WITH PASSWORD 'CHANGE_ME_STRONG_PASSWORD';"
sudo -u postgres psql -c "CREATE DATABASE coryd OWNER coryd_user;"
```

---

## Phase 3 — Confirm the shared Kafka/Redis are reachable

Nothing to install here — just verify CoRyd's services will actually be able to reach marketrun-ng's existing containers on their default host ports:

```bash
# On the VPS - check both the IPv4 and IPv6 loopback explicitly
nc -zv 127.0.0.1 9092    # Kafka
nc -zv 127.0.0.1 6379    # Redis
```

Both should report `succeeded` on `127.0.0.1`. On this VPS, marketrun-ng's Kafka container only publishes on IPv4 (`nc -zv localhost 9092` fails over `::1` but succeeds over `127.0.0.1`) — which is why Phase 4's `.env.dev` template uses `127.0.0.1` explicitly rather than `localhost`: if the JVM's DNS resolution tries `::1` first, `localhost:9092` can fail intermittently on startup even though the broker is up. If a check fails even on `127.0.0.1`, check what host port marketrun-ng's `docker-compose.yml` actually publishes it on and use that value instead.

`docker-compose.yml` in this repo is kept only for **local development** (so you can run Kafka/Redis on your own machine while running a service from your IDE) — it is not deployed to this VPS.

---

## Phase 4 — Environment file

```bash
# From your local machine
scp .env.dev.example devoyeks@<vps-ip>:/opt/coryd-dev/.env.dev

# On the VPS
cd /opt/coryd-dev
nano .env.dev
```

Fill in at minimum:
- `DB_PASSWORD` — the Postgres password you set in Phase 2
- `JWT_SECRET` — generate one: `openssl rand -base64 48`
- `PAYSTACK_SECRET_KEY` — test key to start
- `TERMII_API_KEY` / `TERMII_SENDER_ID`
- `REPORT_ENABLED` / `REPORT_TIMEZONE` / `REPORT_RECIPIENTS` / `REPORT_LOGO_URL` — ops reports emailed at 06:00 Africa/Lagos (daily), Mon 06:05 (weekly), 1st 06:10 (monthly). `REPORT_RECIPIENTS` is comma-separated.
- `GOOGLE_MAPS_API_KEY`
- `FIREBASE_CREDENTIALS_PATH` — push notifications; see [docs/firebase-fcm.md](firebase-fcm.md) for getting the service-account JSON onto the VPS. Optional to start — if left pointing at a file that doesn't exist yet, `notification-service` still starts fine and just skips push delivery (in-app/DB notifications are unaffected).

`KAFKA_SERVERS=127.0.0.1:9092`, `REDIS_HOST=127.0.0.1`, `REDIS_PORT=6379`, and `REDIS_DATABASE=1` are already correct in the template for the shared-infra setup (confirmed reachable in Phase 3) — only change them if Phase 3's reachability check told you otherwise. `api-gateway` doesn't need this file at all (it has no DB/Kafka/Redis dependency, just routes), but there's no harm in it being present in the same `EnvironmentFile=` for every unit.

**Don't remove `SPRING_PROFILES_ACTIVE=dev`** — without it every service runs under Spring's bare "default" profile, `application-dev.yml` never loads, and identity-service's fixed `000333` OTP (dev/test only) never activates.

**Never commit `.env.dev`.**

---

## Phase 5 — systemd units

```bash
# From your local machine
scp systemd/coryd-*.service devoyeks@<vps-ip>:/tmp/

# On the VPS
sudo mv /tmp/coryd-*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable coryd-identity-service coryd-profile-service coryd-trip-service coryd-payment-service coryd-notification-service coryd-api-gateway
```

Don't `start` them yet — there's no `app.jar` in place until CI ships one in Phase 7. `enable` just means "start on boot once one exists" — it won't error out running against an empty service.

---

## Phase 6 — Passwordless sudo for CI restarts

The GitHub Actions workflow restarts services over SSH as `devoyeks`. `systemctl restart` needs root, and CI has no way to type a sudo password interactively — without this step, every deploy's restart step will hang and time out.

Write the rule directly rather than through visudo's interactive editor — it's easy to exit without anything actually being inserted, which leaves the file empty and silently grants nothing:

```bash
# On the VPS - confirm the exact systemctl path first, sudoers needs an exact match
which systemctl
# typically /usr/bin/systemctl - swap the path below if it printed something else

echo 'devoyeks ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart coryd-*' | sudo tee /etc/sudoers.d/devoyeks-coryd
sudo chmod 0440 /etc/sudoers.d/devoyeks-coryd
```

Note the wildcard is `coryd-*`, not `coryd-*-service` — `coryd-api-gateway` doesn't end in `-service`, so a suffix-anchored pattern would silently exclude it and CI would hang restarting the gateway specifically. `coryd-*` still only matches CoRyd's own units, nothing else on this shared box (not marketrun's, not hospio360's). If you set up this sudoers rule before the gateway existed, re-run this command to widen it.

Validate the syntax before logging out (a bad sudoers file can lock out sudo entirely):
```bash
sudo visudo -c -f /etc/sudoers.d/devoyeks-coryd
# should print: /etc/sudoers.d/devoyeks-coryd: parsed OK
```

---

## Phase 7 — GitHub Actions secrets, then push — CI does the first deploy

In the `coryd-backend` GitHub repo → **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `DEV_VPS_HOST` | VPS IP address |
| `DEV_VPS_USER` | `devoyeks` |
| `DEV_VPS_SSH_KEY` | Private key for `devoyeks` on this VPS |

Then push the repo's `develop` branch (this first push doubles as the first deploy — there's no separate manual step):

```bash
git push origin develop
```

`.github/workflows/deploy-dev.yml` picks it up automatically:
1. **detect-changes** — on a brand-new branch, `dorny/paths-filter` reports every service (and the gateway) as changed, so all 6 build.
2. **build** — one job per module, Maven package, jar renamed to `app.jar`, uploaded as a build artifact.
3. **deploy** — scp's each `app.jar` to `/opt/coryd-dev/incoming/<module>/`, then over SSH: moves it into place at `/opt/coryd-dev/<module>/app.jar` and runs `systemctl restart coryd-<module>`. Since the units are enabled-but-never-started (Phase 5), `restart` just starts them — no special-casing needed for "first" vs. "later" deploys. `api-gateway` restarts **last**, so it only ever routes to backends already on the new version.

Watch it run under the repo's **Actions** tab. If the `deploy` job fails on the restart step specifically, Phase 6's sudoers rule is the first thing to double-check.

To confirm from the VPS side once it's green:
```bash
sudo systemctl status coryd-identity-service coryd-profile-service coryd-trip-service coryd-payment-service coryd-notification-service coryd-api-gateway
journalctl -u coryd-trip-service -f   # tail logs for any service that isn't "active (running)"
```

If a service fails to connect to Kafka/Redis on startup, re-check Phase 3.

---

## Phase 8 — Nginx + Certbot

Nginx proxies everything to the gateway on `127.0.0.1:9100` — a single `location /` block, same shape as marketrun-ng's own site file, rather than one location per service.

```bash
# From your local machine
scp nginx/coryd-api-dev.marketrunn.com devoyeks@<vps-ip>:/tmp/

# On the VPS
sudo mv /tmp/coryd-api-dev.marketrunn.com /etc/nginx/sites-available/
sudo ln -s /etc/nginx/sites-available/coryd-api-dev.marketrunn.com /etc/nginx/sites-enabled/
sudo nginx -t          # validate config before reloading
sudo systemctl reload nginx

# Issue the cert - certbot rewrites the site file in place to add the SSL block
sudo certbot --nginx -d coryd-api-dev.marketrunn.com
```

If you land on this phase after already hitting `ERR_SSL_UNRECOGNIZED_NAME_ALERT` in a browser, that error means this phase was never completed (no certificate/server block exists yet for this hostname) — running the steps above resolves it.

---

## Phase 9 — Verification

```bash
curl https://coryd-api-dev.marketrunn.com/identity/actuator/health
curl https://coryd-api-dev.marketrunn.com/profile/actuator/health
curl https://coryd-api-dev.marketrunn.com/trip/actuator/health
curl https://coryd-api-dev.marketrunn.com/payment/actuator/health
curl https://coryd-api-dev.marketrunn.com/notification/actuator/health
```

Each should return `{"status":"UP"}` — the gateway forwards the same path prefixes through unchanged, so these URLs are identical to what they'd be without a gateway in front.

Unified Swagger UI (the payoff for adding the gateway):
```
https://coryd-api-dev.marketrunn.com/swagger-ui.html
```
This should show a dropdown listing all 5 services, each backed by its own `/docs/<service>/v3/api-docs` route.

Also worth a spot-check the first time: `docker exec` into marketrun-ng's Kafka container (or use `kafka-consumer-groups.sh --list`) and confirm you see `coryd-identity-service`, `coryd-profile-service`, etc. as their own distinct consumer groups, separate from marketrun-ng's `identity-service`, `profile-service`, etc.

---

## Rollback

There's no image registry here, and rollback is the one case that stays manual (not something to automate casually against a shared box):

```bash
git checkout <previous-good-sha>
mvn -pl trip-service -am package -DskipTests -q
scp trip-service/target/*.jar devoyeks@<vps-ip>:/opt/coryd-dev/trip-service/app.jar
ssh devoyeks@<vps-ip> "sudo systemctl restart coryd-trip-service"
```

## Adding a 7th module later

1. Add a route block to `api-gateway/src/main/resources/application.yml` (both the main route and its `/docs/<name>/**` rewrite), plus an entry in `springdoc.swagger-ui.urls`.
2. Add a `systemd/coryd-<name>.service` unit (copy an existing one, change the description/path).
3. Add the module to `.github/workflows/deploy-dev.yml`'s path filter, matrix, and restart loop (before `api-gateway`, which must stay last).
4. If it's a data-owning service: prefix its Kafka topics/group ID with `coryd-`, same as the other 5.
5. `mkdir /opt/coryd-dev/<name>` on the VPS, `systemctl enable` its unit — no sudoers change needed, Phase 6's `coryd-*` wildcard already covers it.

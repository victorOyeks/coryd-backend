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

Domain: `api-dev.coryd.ng` → Nginx → `api-gateway` (:9100) → the services (:9101-9106), mirroring marketrun-ng's own gateway pattern. This also gives a single unified Swagger UI instead of 5 separate ones.

**Everything after Phase 6 is CI-driven** — GitHub Actions builds, ships, and starts all 6 (5 services + gateway) on the first push to `develop`, and every push after that. Phases 1-6 are one-time setup that only you can do (DNS/GitHub/VPS access), since I have none of those from here.

---

## Phase 1 — DNS (`coryd.ng` zone)

1. Open DNS management for `coryd.ng`, wherever the domain's nameservers point (Cloudflare or the registrar).
2. Add a record. Type: `A`. Name: `api-dev`. IPv4 address: the VPS's public IP (`curl -4 ifconfig.me` on the VPS - currently `62.169.21.189`).
3. If the zone is on Cloudflare: proxy status **DNS only** (grey cloud) until the certificate is issued - Certbot validates directly against the origin.
4. Save, then confirm from your machine before moving on: `dig +short api-dev.coryd.ng` must print the VPS IP.

---

## Phase 2 — VPS: directory layout + Postgres

SSH in as `devoyeks` (or whichever user has sudo on this box):

```bash
ssh devoyeks@<vps-ip>

# Confirm CoRyd's app ports are actually free before relying on them -
# marketrun uses 9000-9007, but hospio360/his-BE-staging ports aren't known
# from here.
sudo ss -tlnp | grep -E ':(9100|9101|9102|9103|9104|9105|9106) '
# ^ should print nothing. If it prints something, pick different ports and
# update application.yml (6 modules) and the nginx site file accordingly.

# App directory - one subfolder per service (including the gateway),
# matching marketrun's layout
sudo mkdir -p /opt/coryd-dev/{api-gateway,identity-service,profile-service,trip-service,payment-service,notification-service,ops-service,incoming}
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
- `NDPC_ALERTS_ENABLED` (true) / `NDPC_WARN_PERCENT` (80) / `NDPC_CHECK_CRON` (06:30 daily) (notification-service) — NDPC registration tier alerts. Every day it counts everyone CoRyd holds personal data about (registered accounts + beta waitlist sign-ups who haven't joined, all time) and emails `REPORT_RECIPIENTS` once when the count nears a tier (80% of it) and once when it reaches it: Ordinary-High 200 (₦10,000/yr), Extra-High 1,000 (₦100,000/yr), Ultra-High 5,000 (₦250,000/yr). The tiers and fees are in `NdpcThresholdProperties` if NDPC changes them.
- `SOS_OPS_PHONES` / `SOS_OPS_EMAILS` — every SOS is texted to each ops phone and emailed to each ops address, comma-separated. Leave both empty and ops hear nothing (the service logs an error on each SOS).
- `IDENTITY_VERIFICATION_ENFORCEMENT` (trip-service) — `STRICT` (default): nobody books (riders) or posts routes (drivers) until their NIN + selfie check is approved, by Smile ID or ops (Ops Console identity queue). `OFF` only where nobody can be verified yet. On dev, the seeded test accounts start verified.
- `SMILE_ID_PARTNER_ID` / `SMILE_ID_API_KEY` / `SMILE_ID_SANDBOX` (profile-service) — the Smile ID account that runs the NIN + live selfie check (everyone) and the driver's licence check. **Dev uses Smile ID's sandbox**: a Sandbox API key with `SMILE_ID_SANDBOX=true`. It's free, and every check is sent as Smile ID's test identity (`SMILE_ID_TEST_GIVEN_NAMES` / `_LAST_NAME` / `_EMAIL`, default Amina Fatou Clearwater) and always approves. **Production uses live**: a Production API key with `SMILE_ID_SANDBOX=false`, with real checks paid from the Smile ID wallet. In the Smile ID portal, set each environment's callback URL to `https://<api host>/profile/webhooks/smile-id` (Sandbox for dev, Production for production). `SMILE_ID_CALLBACK_URL` overrides it (leave blank), `SMILE_ID_ID_TYPE` is `NIN_V2` unless Smile ID says to use `NIN_SLIP`, `SMILE_ID_NAME_MATCH_THRESHOLD` defaults to `0.85`, `SMILE_ID_PARTIAL_NAME_MATCH` (`APPROVE` default, or `OPS`) decides whether a name sharing only one name with the record is approved and listed for ops to review, or held for ops. Remove any old `PREMBLY_*` lines. See [smile-id-verification.md](smile-id-verification.md).
- `SMILE_ID_DRIVERS_USE_NIN_LOOKUP` (true), `SMILE_ID_NIN_LOOKUP_MAX_ATTEMPTS` (3), `SMILE_ID_NIN_LOOKUP_COOLDOWN_SECONDS` (30), `NIN_FINGERPRINT_KEY` (profile-service) — drivers confirm their NIN by number only (Smile ID Enhanced KYC, no selfie) and match their face on the licence check; riders keep the NIN + selfie check. Set `SMILE_ID_DRIVERS_USE_NIN_LOOKUP=false` until the driver app with the lookup is released, so older builds keep working. **`NIN_FINGERPRINT_KEY` must be set to a long random value in every environment and never changed**: it keys the one-way fingerprint used to spot the same NIN on two accounts and numbers NIMC already said don't exist (the NIN itself is never stored).
- `FARE_PER_KM` (100), `FARE_MINIMUM` (500), `FARE_ROUND_TO` (100) (trip-service) — the fare suggested to drivers while posting: the length of their whole road in km × `FARE_PER_KM`, rounded, never below `FARE_MINIMUM`. Change the rate here without a release.
- `TRIP_SAFETY_ENABLED`, `TRIP_SAFETY_STOP_MINUTES` (10), `TRIP_SAFETY_STOP_RADIUS_METERS` (50), `TRIP_SAFETY_KNOWN_STOP_RADIUS_METERS` (300), `TRIP_SAFETY_OFF_ROUTE_METERS` (1000), `TRIP_SAFETY_OFF_ROUTE_MINUTES` (5), `TRIP_SAFETY_SIGNAL_LOST_MINUTES` (10), `TRIP_SAFETY_ANSWER_MINUTES` (3), `TRIP_SAFETY_REPEAT_MINUTES` (60), `TRIP_SAFETY_WATCH_GRACE_MINUTES` (60) (trip-service) — the "Are you OK?" checks. The same kind of check is raised at most once per trip per repeat window, and a trip is only watched until its expected arrival plus the grace period (after that the stuck-trip job flags it for ops). Unanswered checks go to the same `SOS_OPS_PHONES` / `SOS_OPS_EMAILS`. `ROUTE_TEMPLATES_CRON` (default hourly at :05) is how often regular routes post their trips.
- `MATCHING_ALONG_ROUTE_ENABLED` / `MATCHING_WALK_METERS` / `MATCHING_MAX_TRIP_MINUTES` (trip-service, defaults `true` / `1000` / `240`) — along-the-way matching: riders whose start and end are both within the walk of a driver's road, in order. Switching it off also lifts the "pickup must be on the route" rule.
- `MATCHING_TIME_TOLERANCE_MINUTES` / `MATCHING_GEO_TOLERANCE_METERS` (trip-service, defaults `60` / `2000`) — how close a trip's time and ends must be to a rider's for search and notify-me. Commuter defaults; widen only if searches come back empty too often (pilot metrics: search success rate).
- `BETA_FORM_ALLOWED_ORIGINS` (api-gateway, default `https://coryd.ng,https://www.coryd.ng`) — the website addresses allowed to post the public beta waitlist form (`POST /identity/beta-signups`) from a browser. The form sends `name`, `whatsappNumber`, `email` (required - the address added to TestFlight / Play testing), `willMostly` (RIDE | DRIVE), `usualRoute` and `device` (IPHONE | ANDROID). Add any other address the page is served from.
- **PostGIS (along-the-way matching).** trip-service uses PostGIS when the database has it, and matches in Java otherwise - it starts either way. To turn it on, once, as the Postgres admin (the app user isn't a superuser):
  ```bash
  sudo apt install postgresql-16-postgis-3   # match the server's Postgres major version
  sudo -u postgres psql -d coryd -c "CREATE EXTENSION IF NOT EXISTS postgis SCHEMA public;"
  sudo systemctl restart coryd-trip-service   # the next start adds the road column, its index and trigger
  ```
  Install it in `public`: trip-service's connection searches `trip,public`. The log line "Along-the-way matching uses PostGIS (indexed)" confirms it (on the first search). `MATCHING_USE_POSTGIS=false` forces the Java path.


### Firing a report on demand

OPS_ADMIN token required. `date` is any day inside the period to report on; omit it for the
current day/week/month, so a report can be fired before the period has finished.

```bash
# send now, for today / this week / this month
curl -X POST "$GATEWAY/notification/admin/reports/DAILY"   -H "Authorization: Bearer $OPS_TOKEN"
curl -X POST "$GATEWAY/notification/admin/reports/WEEKLY"  -H "Authorization: Bearer $OPS_TOKEN"
curl -X POST "$GATEWAY/notification/admin/reports/MONTHLY" -H "Authorization: Bearer $OPS_TOKEN"

# re-send a specific past period
curl -X POST "$GATEWAY/notification/admin/reports/DAILY?date=2026-09-13" -H "Authorization: Bearer $OPS_TOKEN"

# send to one address instead of REPORT_RECIPIENTS
curl -X POST "$GATEWAY/notification/admin/reports/DAILY?to=victor.oyeks@coryd.ng" -H "Authorization: Bearer $OPS_TOKEN"

# render in the browser without sending (costs no email quota)
curl "$GATEWAY/notification/admin/reports/WEEKLY/preview?date=2026-09-13" -H "Authorization: Bearer $OPS_TOKEN" > report.html
```
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
sudo systemctl enable coryd-identity-service coryd-profile-service coryd-trip-service coryd-payment-service coryd-notification-service coryd-ops-service coryd-api-gateway
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
sudo systemctl status coryd-identity-service coryd-profile-service coryd-trip-service coryd-payment-service coryd-notification-service coryd-ops-service coryd-api-gateway
journalctl -u coryd-trip-service -f   # tail logs for any service that isn't "active (running)"
```

If a service fails to connect to Kafka/Redis on startup, re-check Phase 3.

---

## Phase 8 — Nginx + Certbot

Nginx proxies everything to the gateway on `127.0.0.1:9100` — a single `location /` block, same shape as marketrun-ng's own site file, rather than one location per service.

```bash
# From your local machine
scp nginx/api-dev.coryd.ng devoyeks@<vps-ip>:/tmp/

# On the VPS
sudo mv /tmp/api-dev.coryd.ng /etc/nginx/sites-available/
sudo ln -s /etc/nginx/sites-available/api-dev.coryd.ng /etc/nginx/sites-enabled/
sudo nginx -t          # validate config before reloading
sudo systemctl reload nginx

# Issue the cert - certbot rewrites the site file in place to add the SSL block
sudo certbot --nginx -d api-dev.coryd.ng
```

If you land on this phase after already hitting `ERR_SSL_UNRECOGNIZED_NAME_ALERT` in a browser, that error means this phase was never completed (no certificate/server block exists yet for this hostname) — running the steps above resolves it.

---

## Phase 9 — Verification

```bash
curl https://api-dev.coryd.ng/identity/actuator/health
curl https://api-dev.coryd.ng/profile/actuator/health
curl https://api-dev.coryd.ng/trip/actuator/health
curl https://api-dev.coryd.ng/payment/actuator/health
curl https://api-dev.coryd.ng/notification/actuator/health
curl https://api-dev.coryd.ng/ops/actuator/health
```

Each should return `{"status":"UP"}` — the gateway forwards the same path prefixes through unchanged, so these URLs are identical to what they'd be without a gateway in front.

Unified Swagger UI (the payoff for adding the gateway):
```
https://api-dev.coryd.ng/swagger-ui.html
```
This should show a dropdown listing all 5 services, each backed by its own `/docs/<service>/v3/api-docs` route.

Also worth a spot-check the first time: `docker exec` into marketrun-ng's Kafka container (or use `kafka-consumer-groups.sh --list`) and confirm you see `coryd-identity-service`, `coryd-profile-service`, etc. as their own distinct consumer groups, separate from marketrun-ng's `identity-service`, `profile-service`, etc.

---

## Settings outside this repo that point at `api-dev.coryd.ng`

If the host ever changes again, these are the places that have to follow it:

- **`.env.dev`** - `SHARE_LINK_BASE_URL=https://api-dev.coryd.ng/trip`. The trip-share links texted to
  emergency contacts are built from it; restart `coryd-trip-service` after changing it.
- **Paystack dashboard** (Test mode) -> Settings -> API Keys & Webhooks -> Test Webhook URL:
  `https://api-dev.coryd.ng/payment/payments/webhook`. Card payments confirm through it, and it's
  how riders' cards get saved for one-tap payment.
- **The rider/driver app and the Ops Console** - their API base URL.

---

## ops-service (the Ops Console backend)

`ops-service` (:9106, `/ops/**`) is the backend for the Ops Console. It **has no database of its own** -
every read and every approve/reject goes through the service that owns the data, carrying the ops
admin's own token, so that service's `OPS_ADMIN` checks still apply. Every endpoint requires an
`OPS_ADMIN` session. It needs only what is already in `.env.dev` (`JWT_SECRET`, `INTERNAL_API_KEY`,
`KAFKA_SERVERS`, the `*_SERVICE_URL` defaults) - no Postgres user, schema or Liquibase.

### Rolling it out to a VPS that's already running the other services

Do this **before** pushing the commit that adds it - CI's deploy step restarts `coryd-ops-service`,
and that fails if the unit file isn't installed yet:

```bash
# From your local machine
scp systemd/coryd-ops-service.service devoyeks@<vps-ip>:/tmp/

# On the VPS
sudo ss -tlnp | grep ':9106 '          # should print nothing
sudo mkdir -p /opt/coryd-dev/ops-service && sudo chown devoyeks:devoyeks /opt/coryd-dev/ops-service
sudo mv /tmp/coryd-ops-service.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable coryd-ops-service
```

No sudoers change - Phase 6's `coryd-*` wildcard already covers it. Nginx needs nothing either: it
forwards everything to the gateway, and the gateway routes `/ops/**`. Then push; CI builds and starts
it (it restarts before `api-gateway`, which picks up the new route on its own restart). Check with:

```bash
curl https://api-dev.coryd.ng/ops/actuator/health     # {"status":"UP",...}
curl -i https://api-dev.coryd.ng/ops/drivers/verification/queue   # 401 without a token
```

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

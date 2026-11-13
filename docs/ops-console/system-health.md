# Ops Console: System & job health

Sidebar: **System health** (PRD module 18). One call: are the services up, is Kafka keeping up, did the background jobs
run, are the providers (Paystack, Smile ID, Termii, FCM, SMTP) failing.

**Basics:** base URL `https://api-dev.coryd.ng/ops`; `OPS_ADMIN` token (none → `401`, other role → `403`); usual envelope.

## `GET /system/health?hours=24`
`hours` (1–720, default 24) is the window for provider failure counts. Takes a few seconds at most - each part has its own
timeout and a part that doesn't answer is reported, not fatal.

```json
{
  "at": "2026-11-13T10:00:00Z", "windowHours": 24, "overall": "DEGRADED",
  "alerts": [
    { "level": "WARNING", "kind": "JOB", "message": "trip-service RouteExpiryJob.run failed its last run: IllegalStateException: …" },
    { "level": "WARNING", "kind": "INTEGRATION", "message": "TERMII sms: 6 of 10 failed in the last 24h" }
  ],
  "services": [
    { "service": "ops-service", "status": "UP" },
    { "service": "trip-service", "status": "UP", "components": { "db": "UP", "diskSpace": "UP", "kafkaConsumers": "UP", "ping": "UP" } },
    { "service": "payment-service", "status": "DOWN" },
    { "service": "identity-service", "status": "UNREACHABLE" }
  ],
  "kafka": { "reachable": true, "error": null,
             "groups": [ { "group": "coryd-notification-service", "state": "STABLE", "members": 1, "lag": 0, "ephemeral": false,
                           "lagByTopic": { "coryd-otp-issued": 0, "coryd-booking-confirmed": 0 } } ] },
  "jobs": [
    { "service": "trip-service", "job": "RouteExpiryJob.run", "schedule": "every 300s after the last run",
      "lastRun": { "job": "RouteExpiryJob.run", "lastStartedAt": "…", "lastFinishedAt": "…", "lastDurationMs": 84, "lastStatus": "OK",
                   "lastError": null, "lastSuccessAt": "…", "lastFailureAt": null, "runs": 1520, "failures": 0 } },
    { "service": "notification-service", "job": "ReportJob.daily", "schedule": "cron 0 0 6 * * *", "lastRun": null }
  ],
  "integrations": [
    { "service": "payment-service", "integration": "PAYSTACK", "operation": "charges", "attempts": 40, "failures": 2, "lastFailureAt": "…", "lastError": null },
    { "service": "profile-service", "integration": "SMILE_ID", "operation": "verification checks", "attempts": 6, "failures": 0, … },
    { "service": "notification-service", "integration": "TERMII", "operation": "sms", "attempts": 10, "failures": 6, "lastError": "…" },
    { "service": "notification-service", "integration": "FCM", "operation": "push", … },
    { "service": "notification-service", "integration": "SMTP", "operation": "email", … }
  ]
}
```

- **overall:** `OK` (no alerts) · `DEGRADED` (warnings) · `DOWN` (any critical). Only real problems count: a service not `UP`, a real consumer group that stopped with messages waiting - not abandoned live-stream groups (below).
- **services:** each service's `/actuator/health` (status and component statuses). `UNREACHABLE` = no answer in 4 s. Not `UP` → `CRITICAL` alert. `kafkaConsumers` is common's check that every listener container is still running.
- **kafka:** every consumer group whose name starts `coryd-` on the (shared) broker: state, members, `ephemeral`, and lag = end offset − committed offset, summed and per topic. Lag ≥ 1,000 → `WARNING`; lag with no members → `CRITICAL`. Unreachable broker → `reachable: false` and a warning.
  - **Ephemeral groups never alert** (phase 7). ops-service's live stream (`GET /ops/stream`) reads Kafka in a group of its own per
    instance start, `coryd-ops-stream-<random>`. Each restart used to leave the old group behind, EMPTY, its "lag" growing forever -
    read as a stopped consumer, so System Health showed **DOWN** for nothing. These groups (prefixes in
    `ops.health.ephemeral-group-prefixes`, default `coryd-ops-stream-`) are listed with `"ephemeral": true` and skipped by the lag alerts.
  - ops-service also cleans them up: on shutdown (after its listener stops) it deletes its own group, and at startup and hourly it
    deletes any other `coryd-ops-stream-*` group that is EMPTY or DEAD (left by a crash). A group with members - another running
    instance - is never touched. Best effort: an unreachable broker is logged and skipped (`ops.stream.group-cleanup=false` turns it off).
- **jobs:** every `@Scheduled` job in trip-, payment-, profile- and notification-service, with its schedule, and its last run from that service's `job_runs` table (`lastRun: null` = hasn't run since this was deployed). Named `Class.method`:
  route expiry `RouteExpiryJob.run` · seat holds `SeatHoldExpiryJob.releaseExpiredHolds` · departure reminders `RouteDepartureReminderJob.sendUpcomingDepartureReminders` · suggested fares `SuggestedFareJob.recalculate` · SOS re-alert `SosRealertJob.run` · safety checks `TripSafetyJob.run` · stats resync `DriverStatsPublisher.syncAll`, `RiderStatsPublisher.syncAll`, `EarningsPublisher.syncAll`, `DriverIndexJobs.rebuildAll`, `RiderIndexJobs.rebuildAll` · settings `PlatformSettingsService.applyDue` · refund retry `RefundRetryJob.retry` · settlement sync `SettlementSyncService.syncAll` · document expiry `DocumentExpiryJob.checkExpiringDocuments` · reports `ReportJob.daily/weekly/monthly`, `NdpcThresholdJob.daily` · broadcasts `BroadcastSender.dispatchDue` (and more).
  A last run that `FAILED` → `WARNING`. Some jobs catch their own errors and log them, so they record `OK`; their failures show in the provider counts instead.
- **integrations:** counted from what each service already records, over the window:
  - **PAYSTACK** (payment-service): `charges` (payment transactions, `FAILED`), `refunds` (`FAILED`), `settlements` (driver settlements `failed`), `ops refunds and retries` (ops money requests with a failure reason, `lastError` = the latest).
  - **SMILE_ID** (profile-service): verification checks; failures = `PROVIDER_ERROR` (Smile ID or our call failed - not the driver's fault). `lastError` = the provider summary.
  - **TERMII** (`sms`, `voice`), **TERMII_WHATSAPP**, **FCM** (`push`), **SMTP** (`email`) (notification-service): `notification_log` rows; failure = not accepted by the provider; `lastError` = the provider's error code where recorded.
  - At least 5 attempts with ≥ 20% failed → `WARNING`.
  - **Every provider is always listed** (phase 7): one with nothing in the window shows `attempts: 0, failures: 0` instead of being
    missing - PAYSTACK (charges, refunds, settlements, ops refunds and retries), SMILE_ID (verification checks), TERMII (sms, voice),
    TERMII_WHATSAPP (whatsapp), FCM (push), SMTP (email). A service that didn't answer has its row under `jobs` with an `error`.

## How job runs are recorded

Each service with a database has a `job_runs` table (trip coryd:131, payment :133, profile :134, notification :137), one row per
job, upserted at the end of every run. Nothing in the jobs changed: Spring Boot observes every `@Scheduled` invocation and
common's `ScheduledJobObservationHandler` writes the row (`JobRunRecorder`, switched on by `coryd.job-runs.schema`). A
recording failure is logged and never fails the job. The per-service view is `GET /<service>/internal/system/status?hours=`
(internal key, any authenticated caller).

Not covered yet: WhatsApp/SMS balance or quota (US-18.3 - Termii's balance API isn't called anywhere), identity-service (no scheduled jobs).

# Onboarding reminders and the verification digest

Two daily jobs in **notification-service**:

1. **Onboarding reminders** - riders and drivers who haven't finished signing up, and are waiting on *themselves*, get one
   push and one entry in their notifications naming their next step.
2. **Verification digest** - when anyone has submitted and is waiting on a verification officer, the officers get one
   morning email listing them.

Both are switched on, timed and tuned in **Settings** (Platform Settings, groups `ONBOARDING` and `NOTIFICATIONS` - see
[settings.md](settings.md)), and a super admin can run either by hand (section 5).

**Why notification-service:** it already owns the device tokens, the in-app feed (`user_notifications`), notification
preferences, quiet hours, `EmailService` and `notification_log`, and has a database for the per-person tracking. It asks the
services that own the facts: identity-service (who has an account, when they signed up and accepted the terms, the ops team)
and profile-service (where each person stands in onboarding, who is waiting on an officer). ops-service has no database.

---

## 1. Onboarding reminders

### Who is considered (identity-service `GET /internal/onboarding/candidates`)
Accounts with the `RIDER` and/or `DRIVER` role that can sign in: phone verified, password set, `active`, not suspended, no
account deletion request `PENDING`. **Left out:** suspended or deactivated accounts, accounts waiting to be deleted, phone
not verified or no password yet (they can't sign in, so have no device and can't see the feed), ops-only admins.

### Their steps (profile-service `POST /internal/onboarding/status`, plus the terms from identity)
The steps, in the order they are asked for - a reminder names the **first** outstanding one:

| `step` | Riders | Drivers | Outstanding when |
|---|---|---|---|
| `PROFILE` | ✓ | ✓ | Rider: no rider profile. Driver: no driver profile, or no name / date of birth. |
| `TERMS` | ✓ | ✓ | Terms of service not accepted (identity `terms_accepted_at`). |
| `NIN` | ✓ | ✓ | Identity check never started, or left half-way (`reason: NOT_DONE`); Smile ID failed it and asked for another try, or ops rejected it (`RETRY`). |
| `NIN_LOCKED_OUT` | ✓ | ✓ | Every NIN try used - the message says to contact support (`reason: LOCKED_OUT`). |
| `LICENCE` | | ✓ | Not uploaded (`NOT_DONE`), Smile ID asked for a retake (`RETRY`), ops asked for it again (`RESUBMIT`), rejected (`REJECTED`), expired before approval (`EXPIRED`). |
| `VEHICLE` | | ✓ | Vehicle details and registration: not added, `RESUBMIT`, `REJECTED`. |
| `INSURANCE` | | ✓ | Insurance certificate: as the licence. |
| `ROADWORTHINESS` | | ✓ | Roadworthiness certificate: as the licence. |

The identity step reads the person's tab on the ops identity screen ([rider-identity.md](rider-identity.md)); the driver's four
items are the verification queue's ([verification-queue.md](verification-queue.md)).

**Not their turn - not a step:** an identity check `IN_REVIEW`, a check still out with Smile ID, a driver whose NIN is confirmed
and is waiting only for the licence, a document `IN_REVIEW`, a licence still with Smile ID.

### Who is skipped
- **Finished:** rider - profile, terms and NIN approved; driver - approved at least once (`verifiedSince`), and terms accepted.
  Expiring documents after that are the document-expiry reminders' job ([document-expiry.md](document-expiry.md)).
- **Waiting on ops:** nothing left that is theirs to do, and something is in review (or with Smile ID).
- **Not due** today (section 2), or already reminded today.
- **No device token:** still reminded - the in-app entry is written, there is just no push.

Someone with **both roles** gets one reminder: the driver side if it has a step for them, otherwise the rider side.

### What they get
One feed entry and one push to each of their devices, per run - never several.

- **Title:** "You're almost there, Ada" (or "You're almost there"); locked out: "Let's get you verified".
- **Body** names the step, e.g. "Upload your insurance certificate to start driving.", "Verify your NIN to start booking
  rides.", "CoRyd asked you to upload your roadworthiness certificate again. Upload it to start driving.", "Your NIN check needs
  another try. Verify your NIN to start booking rides.", locked out: "We couldn't verify your NIN in the app. Contact CoRyd
  support and we'll help you finish setting up your account."

### Push data contract (mobile apps)
```json
{ "type": "ONBOARDING_REMINDER", "step": "INSURANCE", "reason": "RESUBMIT", "role": "DRIVER" }
```
- `type`: always `ONBOARDING_REMINDER`.
- `step`: `PROFILE` · `TERMS` · `NIN` · `NIN_LOCKED_OUT` · `LICENCE` · `VEHICLE` · `INSURANCE` · `ROADWORTHINESS` - the screen to
  open. `NIN_LOCKED_OUT` should open support / contact, not the NIN flow.
- `reason`: `NOT_DONE` · `RETRY` · `RESUBMIT` · `REJECTED` · `EXPIRED` · `LOCKED_OUT` - optional context, the app may ignore it.
- `role`: `RIDER` or `DRIVER` - which side of the app the step belongs to (switch role first if the user is signed in as the other).
- New values may be added; an app that doesn't know a `step` should open its onboarding checklist.

**Feed** (`GET /notification/notifications/{userId}`): the entry has `eventType: "ONBOARDING_REMINDER"` and two new fields,
`step` and `role` (null on every other notification).

### Notification preferences and quiet hours
Onboarding reminders are **service messages** about the person's own account, not announcements: the push goes whatever
their `promotionalOptIn` (which governs broadcasts only). `whatsappOptIn` doesn't apply (no WhatsApp/SMS is sent). There is no
onboarding-specific opt-out. **Quiet hours** are kept: the scheduled run never pushes inside the broadcast quiet hours
(default 21:00-07:00 Lagos, `broadcast.quiet-hours-*`) - a reminder time inside them waits until they end; a real run by
hand inside them is refused. Someone with **no device token** still gets the feed entry.

## 2. Cadence

Counted in **Lagos days** from the **anchor**: when they signed up, or last made onboarding progress, whichever is later.
Progress = the latest of: terms accepted, any change to their rider/driver profile, identity check (or its Smile ID check),
vehicle or documents - including ops decisions on them (a rejection starts the daily stretch again).

| Day after the anchor | Reminder |
|---|---|
| 0 (the anchor day) | none - they may be signing up right now |
| 1 to `onboarding.reminder-daily-days` (14) | every day |
| after that | every `onboarding.reminder-then-every-days` (3) days since the last reminder (days 17, 20, 23… with the defaults) |

Until they finish. At most one reminder per person per Lagos day.

**Tracking and idempotency:** `notification.onboarding_reminders` (coryd:171), one row per person: `last_reminded_on`
(Lagos date), `last_reminded_at`, `reminder_count`, `last_step`, `last_reason`, `role`, `anchor_at`. A person is **claimed**
for the day by one `INSERT … ON CONFLICT DO UPDATE … WHERE last_reminded_on < today` before anything is sent, so a restart,
a second run the same day, a run by hand or a second instance never sends twice. (A send that fails after the claim is not
retried that day.)

## 3. Verification digest

**Who is waiting** (profile-service `GET /internal/onboarding/verification-waiting`, the same queries as the console screens):
- drivers in the verification queue (pending / in review / resubmitted, incl. a licence with Smile ID long enough to queue),
- identity checks `IN_REVIEW` (riders and drivers),
- partial name matches nobody has reviewed.
At most 500 of each kind are listed; the counts are exact ("And N more - see the console").

**The email** - one per recipient, subject `Verification: 7 waiting (2 over 3 days) · Mon 16 Nov`; HTML in the ops report
layout plus a plain-text part:
- counts at the top (total, driver documents, identity checks, partial name matches),
- one row per item, **oldest wait first**: name and ref (`D-4F2A91` / `R-0DE4B2`) linked to the console, what is waiting
  ("Insurance certificate (re-uploaded)", "Identity (NIN) check - name mismatch", "Partial name match on NIN check - approved,
  needs a look"), submitted at (Lagos), how long waiting (from when the oldest still-waiting part arrived),
- rows waiting **more than `verification.digest-highlight-days` (3) days** highlighted (amber; `!` in the text part).
- Links: `https://admin.coryd.ng/verification-queue/{driverId}` for drivers, `https://admin.coryd.ng/identity-checks?person={userId}`
  for identity checks and partial matches (`coryd.ops.console-url`, env `OPS_CONSOLE_URL`).
- **No email when nobody is waiting.** Sent every day until the queue is empty.

**Recipients:** every ops admin with status `ACTIVE` and ops role `VERIFICATION` or `SUPER_ADMIN` (identity
`GET /internal/ops-admins`) who has an email, plus `verification.digest-extra-recipients`; lower-cased, each once. Each send is
a `notification_log` row (`channel EMAIL`, `eventType VERIFICATION_DIGEST`).

## 4. Settings

| key | group | type | default (env) | range |
|---|---|---|---|---|
| `onboarding.reminders-enabled` | ONBOARDING | BOOLEAN | `true` (`ONBOARDING_REMINDERS_ENABLED`) | true / false |
| `onboarding.reminder-time` | ONBOARDING | TIME_OF_DAY | `08:00` (`ONBOARDING_REMINDER_TIME`) | 00:00-23:59 Lagos |
| `onboarding.reminder-daily-days` | ONBOARDING | INTEGER | `14` (`ONBOARDING_REMINDER_DAILY_DAYS`) | 1-60 days |
| `onboarding.reminder-then-every-days` | ONBOARDING | INTEGER | `3` (`ONBOARDING_REMINDER_EVERY_DAYS`) | 1-30 days |
| `verification.digest-enabled` | NOTIFICATIONS | BOOLEAN | `true` (`VERIFICATION_DIGEST_ENABLED`) | true / false |
| `verification.digest-time` | NOTIFICATIONS | TIME_OF_DAY | `08:00` (`VERIFICATION_DIGEST_TIME`) | 00:00-23:59 Lagos |
| `verification.digest-highlight-days` | NOTIFICATIONS | INTEGER | `3` (`VERIFICATION_DIGEST_HIGHLIGHT_DAYS`) | 1-30 days |
| `verification.digest-extra-recipients` | NOTIFICATIONS | EMAIL_LIST | `none` (`VERIFICATION_DIGEST_EXTRA_RECIPIENTS`) | up to 10 addresses |

Changed, scheduled, audited and propagated like every other setting (trip-service owns them). The env vars must be set for
both trip-service (the default it shows) and notification-service (the default it uses) - they share one env file on the VPS.

**When the jobs run:** notification-service checks every minute (`NOTIFICATION_JOBS_CHECK_MS`). A job runs at the first check
at or after its time on a Lagos day it hasn't run yet - so a time change applies the same day, and a service that was down at
08:00 catches up when it is back. `notification.daily_job_runs` (coryd:172) holds one row per job per day (status
`RUNNING`/`DONE`/`FAILED`, attempts, `recipients`, `items`, error): it is claimed with one insert so only one instance runs it;
a failed run is retried (3 tries), one stuck `RUNNING` for 2 hours is taken over. Each check is also a `job_runs` entry on
System Health (`NotificationDailyJobs.onboardingReminders`, `NotificationDailyJobs.verificationDigest`).

## 5. Running a job by hand (super admins)

Base `https://api-dev.coryd.ng/ops`, `OPS_ADMIN` token with ops role `SUPER_ADMIN` (`PLATFORM_MANAGE`); any other ops role → `403`,
no token → `401`. notification-service checks the role again.

### `POST /notifications/onboarding-reminders/run?dryRun=true|false`
`dryRun` defaults to **true**: works out who would be reminded today and sends and records nothing.
```json
{ "dryRun": true, "day": "2026-11-16", "at": "…", "considered": 120, "reminded": 34,
  "skipped": { "COMPLETE": 70, "WAITING_ON_OPS": 9, "NOT_DUE": 5, "ALREADY_TODAY": 2 },
  "pushesDelivered": 0, "pushesFailed": 0, "withoutDevice": 6,
  "people": [ { "userId": "…", "role": "DRIVER", "step": "INSURANCE", "reason": "RESUBMIT",
                "title": "You're almost there, Ada", "body": "CoRyd asked you to upload …", "reminderNumber": 0,
                "devices": 1, "pushed": 0 } ],
  "truncated": false }
```
`skipped` keys: `COMPLETE`, `WAITING_ON_OPS`, `NOTHING_TO_DO`, `TOO_SOON_AFTER_SIGN_UP`, `ALREADY_TODAY`, `NOT_DUE`. `people`:
the first 200. A real run (`dryRun=false`) reminds only people not yet reminded today, so it is safe to repeat. Refused (`400`)
when reminders are switched off, or inside quiet hours.

### `POST /notifications/verification-digest/run?dryRun=true|false`
```json
{ "dryRun": false, "at": "…", "counts": { "drivers": 4, "identityChecks": 2, "partialNameMatches": 1, "total": 7 },
  "highlighted": 2, "highlightDays": 3, "recipients": [ "vera@coryd.ng", "sam@coryd.ng" ],
  "sent": true, "delivered": 2, "notSentBecause": null, "subject": "Verification: 7 waiting (2 over 3 days) · Mon 16 Nov",
  "rows": [ { "kind": "DRIVER_DOCUMENTS", "userId": "…", "role": "DRIVER", "ref": "D-4F2A91", "name": "Emeka Okafor",
              "what": "Insurance certificate (re-uploaded)", "submittedAt": "…", "waitingSince": "…", "waitingMinutes": 5880,
              "highlighted": true, "link": "https://admin.coryd.ng/verification-queue/…" } ] }
```
`notSentBecause`: "Nobody is waiting for a verification officer", or no recipients. A real run sends even if the scheduled
digest already went today (it was asked for); if it runs **before** the scheduled time, that day's scheduled digest is skipped.
Works even when the digest is switched off.

### Audit
Every real run that sent something, and every real run by hand, is a row in `notification.ops_audit_log`:
`action ONBOARDING_REMINDERS_SENT` / `VERIFICATION_DIGEST_SENT`, `target_type NOTIFICATION_JOB`, `target_id
onboarding-reminders` / `verification-digest`, actor the admin (or `system` / "Scheduled job"), `after_state` the counts
(`considered`, `reminded`, `pushesDelivered`, `withoutDevice`, `skipped` / `waiting`, `listed`, `highlighted`, `recipients`,
`delivered`). They appear in the unified audit feed (`GET /ops/audit`, category `NOTIFICATIONS`, target type
`NOTIFICATION_JOB`; scheduled runs show actor "System") - see [audit-log.md](audit-log.md).

## 6. Internal endpoints (service-to-service, internal key)

| Service | Endpoint | For |
|---|---|---|
| identity | `GET /identity/internal/onboarding/candidates?after&size` (≤1000, keyset by user id) | accounts to consider |
| identity | `GET /identity/internal/ops-admins` (existing) | digest recipients |
| profile | `POST /profile/internal/onboarding/status` `{ "people": [ { "userId", "roles": ["RIDER","DRIVER"] } ] }` (≤500) | steps per role |
| profile | `GET /profile/internal/onboarding/verification-waiting` | digest rows |
| notification | `POST /notification/internal/ops/notifications/{onboarding-reminders,verification-digest}/run?dryRun` | ops-service relay |

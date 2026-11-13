# Ops Console: Platform settings

Sidebar: **Settings** (Configuration, PRD module 14). Also: **Corridors** (read-only, section 6).

Business rules that used to need an env-var change and a redeploy can now be changed from the console. A change applies
straight away or at a time you choose, every service picks it up within seconds, and every change is audited.

**Basics** (same as the rest of the console):
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token. No token: `401`. Another role: `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back `success: false` with a `message` you can show as-is.
- **Who's recorded:** the signed-in admin (id and name), read from their token. You never send it.
- **Times** are ISO-8601 UTC.

---

## 1. The settings

| key | group | type | unit | range | default comes from | used by |
|---|---|---|---|---|---|---|
| `commission.percent` | MONEY | DECIMAL | % | 0 – 25 | `COMMISSION_PERCENTAGE` (fraction: 0.05 = 5%) | payment-service: charge splits, Paystack subaccount split, driver earnings |
| `money.approval-threshold` | MONEY | DECIMAL | NGN | 1,000 – 5,000,000 | `OPS_MONEY_APPROVAL_THRESHOLD` (50000) | payment-service: two-person rule on refunds, retries, debt adjustments |
| `cancellation.late-fee-percent` | CANCELLATION | DECIMAL | % | 0 – 50 | `cancellation.late-fee-percentage` (0.25 = 25%) | trip-service: rider cancellation fee |
| `cancellation.free-window-hours` | CANCELLATION | INTEGER | hours | 0 – 48 | `cancellation.free-cancellation-window-hours` (2) | trip-service: whether a rider cancellation is free |
| `booking.grace-minutes` | BOOKING | INTEGER | minutes | 0 – 60 | `BOOKING_GRACE_MINUTES` (10) | trip-service: `bookableUntil`, search, seat holds |
| `trips.stuck-grace-hours` | TRIPS | INTEGER | hours | 1 – 48 | `trip.stuck.grace-hours` (3) | trip-service: stuck-trip queue, live trips, counts |
| `safety.sos-realert-minutes` | SAFETY | INTEGER_LIST | minutes | each 1 – 240, at most 6 | `SOS_REALERT_MINUTES` (5,15,30) | trip-service: SOS re-alerts to ops |
| `pricing.fare-tolerance-percent` | PRICING | DECIMAL | % | 0 – 100 | `PRICING_FARE_TOLERANCE_PERCENT` (0) | trip-service: pricing monitor outliers (`GET /ops/money/pricing`) |
| `documents.expiry-reminder-days` | DOCUMENTS | INTEGER | days | 7 – 60 | `DOCUMENT_EXPIRY_REMINDER_DAYS` (14) | profile-service: first expiry reminder, the tracker's "expiring soon" window, who "Notify" may remind |
| `documents.final-reminder-days` | DOCUMENTS | INTEGER | days | 1 – 7 | `DOCUMENT_EXPIRY_FINAL_REMINDER_DAYS` (3) | profile-service: last expiry reminder. Must be fewer days than the first reminder. |

- **Values travel as strings** in one spelling: `"5"`, `"7.5"`, `"5,15,30"`. Decimals: at most 2 places. Lists are sorted and de-duplicated for you.
- **`legal: true`** (commission, late fee, free window): the value is in the published Terms of Service. Show the warning the
  PRD asks for, and prefer scheduling. The server adds a `warnings` entry to the response.
- **Default** = what the service uses when ops has never set a value, or after a reset. It's the env var the service always
  read, so turning this feature off changes nothing.
- **Not applied backwards.** A late fee or free window is decided when the rider cancels; a commission when the booking is
  paid (Paystack split) and when the driver is credited. See section 7 for the one edge.

## 2. Read

### `GET /settings`
```json
{ "at": "2026-11-13T10:00:00Z", "timezone": "Africa/Lagos",
  "groups": [ { "group": "MONEY", "settings": [ {
      "key": "commission.percent", "label": "Platform commission", "description": "…",
      "group": "MONEY", "type": "DECIMAL", "unit": "%", "min": 0, "max": 25, "legal": true, "usedBy": "payment-service",
      "defaultSource": "COMMISSION_PERCENTAGE (as a fraction, 0.05 = 5%)",
      "defaultValue": "5", "value": null, "effectiveValue": "5", "overridden": false,
      "scheduled": { "value": "7.5", "effectiveFrom": "2026-12-01T00:00:00Z", "scheduledBy": "…", "scheduledByName": "Ada Ops", "reason": "…" },
      "lastChange": { "id": "…", "key": "commission.percent", "action": "SCHEDULED", "oldValue": "5", "newValue": "7.5",
                      "effectiveFrom": "2026-12-01T00:00:00Z", "actorId": "…", "actorName": "Ada Ops", "reason": "…", "at": "…" },
      "version": 1 } ] } ] }
```
- `value`: what ops set (`null` = default in force). `effectiveValue`: what services use **now**. `scheduled`: `null` unless a change waits.
- Groups and settings come in the order of the table above.

### `GET /settings/{key}` - one setting, same shape. Unknown key → `404` (the message lists the known keys).
### `GET /settings/{key}/history?page&size` - that setting's changes, newest first (`size` max 100).
### `GET /settings/history?page&size` - every setting's changes, newest first.

`action`: `SET` (applied now) · `SCHEDULED` · `APPLIED` (a scheduled change took effect - `actorName` "Scheduled change",
`reason` "Scheduled by Ada Ops: …") · `SCHEDULE_CANCELLED` · `RESET` (back to default; `newValue` is the default).

## 3. Change

### `PUT /settings/{key}`
```json
{ "value": "7.5", "reason": "Board decision 12 Nov", "effectiveFrom": "2026-12-01T00:00:00Z" }
```
- `effectiveFrom`: leave out (or now / past) to apply **now**; a future time **schedules** it (up to a year ahead).
  Anything within 30 seconds of now counts as now.
- Response: `{ setting, change, warnings }`, message `"Setting changed"` or `"Change scheduled"`.

| Refusal (`400` unless noted) | message (example) |
|---|---|
| no / blank `reason` | "Give a reason - it goes in the audit log" (ops-service refuses before calling anyone) |
| no `value` | "Send the new value" |
| out of range / wrong type | "Platform commission must be between 0 and 25 %", "… must be a whole number of minutes", "… can have at most 2 decimal places" |
| same as now | "Rider late-cancellation fee is already 25 %" |
| a change is already scheduled | "… already has a change to 7.5 scheduled for … - cancel it first" |
| final reminder ≥ first reminder | "The final document reminder (7 days) must be fewer days before expiry than the first (7 days)" |
| unknown key | `404` |

### `POST /settings/{key}/cancel-scheduled` `{ "reason": "…" }` - calls off a waiting change. None waiting, or its time has passed → `400`.
### `POST /settings/{key}/reset` `{ "reason": "…" }` - back to the default now. Already default, or a change is scheduled → `400`.

## 4. How it works

- **Owner:** trip-service stores the values (`trip.platform_settings`, one row per setting ever set) and the history
  (`trip.platform_setting_changes`). It validates, records, audits and publishes.
- **Audit:** every change is a row in trip-service's existing `trip.ops_audit_log`: `target_type = SETTING`,
  `target_id = <key>`, `action` `SETTING_CHANGED | SETTING_SCHEDULED | SETTING_APPLIED | SETTING_SCHEDULE_CANCELLED | SETTING_RESET`,
  `before_state` `{"value": "25"}`, `after_state` `{"value": "40", "effectiveFrom": "…"}`, `reason`. Phase 6A's unified audit feed reads it.
- **Propagation:** after the change commits, trip-service publishes the setting's state to the compacted Kafka topic
  **`coryd-platform-settings`** (key = setting key). Every service (common `PlatformSettings`) follows that topic from the
  beginning on its own thread, and also loads a snapshot from trip-service's `GET /trip/internal/settings` at startup and every
  5 minutes, so a service that missed Kafka still converges. Reads are an in-memory lookup.
- **Scheduled changes:** the published record carries `scheduledValue` + `scheduledFrom`, and every service switches at that
  instant on its own clock - no waiting for a job. trip-service's job (every 15 s) then makes it the stored value, writes the
  `APPLIED` history and audit rows and republishes.
- **Fallback:** a service that has never heard from trip-service, or reads an unreadable value, uses its own property - exactly the behaviour before this feature.
- **Commission changes** also re-sync every driver's Paystack subaccount split (`percentage_charge`) the moment the new value takes effect (payment-service, background thread).

## 5. What the mobile app sees

Nothing new to call. Existing fields change with the setting:
- `booking.grace-minutes` → `bookableUntil` on search results and the ride screen (docs/mobile/leaving-now-booking-window.md); trips drop out of search at the new time.
- `cancellation.*` → the fee quoted and charged when a rider cancels.
- `commission.percent` → the driver's earnings breakdown (`GET /payment/earnings/commission-rate` returns the new rate) and their payouts.
- `documents.*` → when drivers get the "your insurance expires in N days" push.
- SOS re-alerts, stuck-trip grace, approval threshold and fare tolerance are ops-only.

## 6. Corridors (read-only)

There is **no stored corridor definition**. Matching and search work on geography (distance from the driver's road), and
pricing groups fares by a corridor key built from the labels drivers post: origin and destination trimmed, whitespace
collapsed, lower-cased, joined by `->` (`RouteService.corridorKey`). The nightly suggested-fare job keeps a range per key once
it has 5 fares. So the "pilot corridor" is implicit - whatever drivers post - and there is nothing to edit.

### `GET /corridors?from&to&limit` (Lagos days, default the last 30; `limit` ≤ 500)
The corridors seen in trips posted in the range, busiest first - the `corridors` report (dashboard-reports.md):
`columns: [corridor, origin, destination, trips, drivers, seats_offered, seats_booked, median_fare, suggested_min, suggested_max, suggested_sample, last_trip_at]`, `rows`, `totalRows`, `truncated`.
Editable suggested ranges per corridor (PRD US-14.4) would need a corridor table first - an open question.

## 7. Known edges

- **Commission and bookings in flight:** a booking paid before the change was split by Paystack at the old rate, but if it
  completes after the change, the driver's earnings statement (payment-service `EarningsService`) records the new rate. Schedule
  commission changes for a quiet hour (e.g. 03:00 Lagos) to keep this small; fixing it needs the rate stored per booking.
- Identity verification policy settings (US-14.3: enforcement mode, grace period, prerequisite level) are not runtime yet.

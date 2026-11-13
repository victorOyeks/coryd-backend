# Ops Console: Dashboard, reports & analytics

Sidebar: **Dashboard** (Overview, PRD module 1) and **Reports** (module 16).

**Basics:** base URL `https://api-dev.coryd.ng/ops`; `OPS_ADMIN` token (none → `401`, other role → `403`); usual envelope.
**Days** (`from`, `to`) are calendar days in **Africa/Lagos**, `YYYY-MM-DD`, both inclusive; at most 366 days; `from` after
`to` → `400`. Money is naira.

---

## 1. Dashboard

### `GET /dashboard?from&to&fresh`
Default range: the last 7 days to today. Three services are asked in parallel (trip, payment, profile) and the result is
cached for 60 s per range (`ops.dashboard.cache-seconds`); `fresh=true` skips the cache. `cachedAt` is set when served from
the cache. If a service doesn't answer, its sections are `null` and it's named in `unavailable` - the rest still shows.

```json
{
  "from": "2026-11-07", "to": "2026-11-13", "timezone": "Africa/Lagos", "generatedAt": "…", "cachedAt": null,
  "trips":   { "posted": 5, "completed": 2, "cancelled": 1, "inProgressNow": 1, "stuckNow": 0,
               "completionRate": 66.7, "cancellationRate": 20.0 },
  "seats":   { "offered": 17, "booked": 6, "bookingRate": 35.3, "avgSeatsFilledPerCompletedTrip": 2.0 },
  "drivers": { "total": 12, "verified": 9, "suspended": 1, "activeLast7Days": 4,
               "activeInRange": 4, "onTripNow": 1, "onlineNow": 2 },
  "riders":  { "total": 40, "suspended": 0, "activeLast7Days": 11, "activeInRange": 3, "repeatInRange": 2 },
  "revenue": { "grossBookings": 4800, "payments": 4, "refunds": 0, "netCollected": 4800,
               "tripGross": 4800, "commission": 240, "driverNet": 4560 },
  "activity": { "made": 5, "confirmedOrUnderway": 1, "completed": 3, "cancelledByRider": 1, "cancelledByDriver": 0, "noShows": 0 },
  "verification": { "driverQueue": { "PENDING": 2, "IN_REVIEW": 1, "RESUBMITTED": 0 },
                    "riderIdentity": { "APPROVED": 30, "NOT_STARTED": 8, "FAILED": 2 } },
  "cancellations": { "bySource": { "RIDER": 3, "DRIVER": 1, "SYSTEM_EXPIRY": 1, "OPS": 0 }, "byKind": { "ROUTE": 2, "BOOKING": 3 } },
  "daily": [ { "date": "2026-11-13", "tripsPosted": 5, "tripsCompleted": 2, "tripsCancelled": 1, "bookings": 5, "seatsBooked": 6,
               "cancellations": 2, "grossFaresCompleted": 4800,
               "grossBookings": 4800, "payments": 4, "refunds": 0, "commission": 240, "payouts": 0 } ],
  "recentActivity": [ { "at": "…", "kind": "OPS_SETTING_CHANGED", "summary": "Setting changed: Riders kept missing it",
                        "actorId": "…", "actorName": "Ada Ops", "targetType": "SETTING", "targetId": "booking.grace-minutes" } ],
  "unavailable": []
}
```

**Definitions** (the daily email's, so the two agree):
- **Trips posted / completed / cancelled:** trips *created* in the range (not drafts), by their status now. `completionRate` = completed ÷ (completed + cancelled); `cancellationRate` = cancelled ÷ posted. Percentages, one decimal, `null` when nothing to divide.
- **Seats:** `offered` = seats on trips posted in the range; `booked` = seats on bookings made in the range (unpaid holds left out); `bookingRate` = booked ÷ offered; `avgSeatsFilledPerCompletedTrip` = completed seats ÷ completed trips.
- **Drivers:** `total`/`verified` (verification `APPROVED`)/`suspended`/`activeLast7Days` (completed a ride in the last 7×24 h) are now; `activeInRange` = posted a trip in the range; `onTripNow` = a trip in progress; `onlineNow` = on a trip, or with a published trip leaving within 2 hours either side of now (there is no "online" toggle in the app).
- **Riders:** `activeInRange` = made a booking in the range; `repeatInRange` = made 2+.
- **Revenue** is payment-service's finance summary (`docs/ops-console/money.md` §finance): `grossBookings` = card payments that succeeded; `commission`/`driverNet` = from trips credited in the range.
- **activity** = bookings made in the range by where they are now.
- **verification.driverQueue** = drivers waiting on ops, by queue tab; **riderIdentity** = riders by ID-check status.
- **cancellations** = Cancellations Log rows in the range (`source`: RIDER, DRIVER, SYSTEM_EXPIRY, SYSTEM, OPS, …).
- **daily** = one row per Lagos day in the range, trip and money figures side by side.
- **recentActivity** = the latest 20 of: ops actions in trip-service's audit log (`OPS_<ACTION>`), trips posted (`TRIP_POSTED`), SOS raised (`SOS_RAISED`, `SAFETY_CHECK_UNANSWERED`), cancellations (`CANCELLATION`). Newest first.

## 2. Reports

### `GET /reports/catalog`
```json
{ "reports": [ { "report": "trips", "title": "Trips", "description": "…", "columns": ["trip_id", "…"], "source": "trip-service", "csv": true }, … ],
  "emailed": [ { "period": "DAILY", "send": "POST /ops/reports/emailed/DAILY/send", "preview": "GET /ops/reports/emailed/DAILY/preview" }, … ],
  "timezone": "Africa/Lagos", "maxRangeDays": 366, "maxCsvRows": 50000, "maxPreviewRows": 500 }
```

| report | one row per | columns |
|---|---|---|
| `trips` | trip posted in the range | trip_id, posted_at, departure_at, driver_id, origin, destination, status, seat_capacity, seats_booked, fare_per_seat, gross_completed |
| `bookings` | booking made in the range (holds left out) | booking_id, booked_at, trip_id, departure_at, rider_id, driver_id, status, seats, fare_total, origin, destination |
| `cancellations` | Cancellations Log row | cancelled_at, kind, source, fault, trip_id, booking_id, rider_id, driver_id, minutes_before_departure, seats, fare_total, paid, fee, refund_expected, reason, backfilled |
| `driver-performance` | driver with trips posted in the range | driver_id, trips_posted, trips_completed, trips_cancelled, completion_rate_pct, seats_offered, seats_booked, fill_rate_pct, gross_completed, avg_rating, ratings (riders' ratings in the range, hidden ones left out) |
| `riders` | rider with bookings in the range | rider_id, bookings, completed, cancelled_by_rider, cancelled_by_driver, no_shows, seats, spent_completed, first_booking_at, last_booking_at |
| `corridors` | origin → destination | see settings.md §6 |
| `revenue` | Lagos day | date, gross_bookings, payments, refunds, commission, payouts (+ `totals`: the finance summary) |
| `verification-funnel` | stage | audience (DRIVERS / RIDERS), stage, people, percent_of_joined - for people who **joined** in the range: drivers JOINED → SUBMITTED_DOCUMENTS → APPROVED (and REJECTED) → COMPLETED_A_TRIP; riders JOINED → STARTED_ID_CHECK → ID_APPROVED (and ID_FAILED_OR_LOCKED) → COMPLETED_A_TRIP |

Times in reports are Lagos local, `YYYY-MM-DD HH:MM`. People are ids (join with the directory for names).

### `GET /reports/analytics/{report}?from&to&limit` - JSON preview
Default range: the last 30 days. `{ report, title, from, to, timezone, columns, rows: [[…]], totalRows, truncated }` -
at most `limit` rows (default 100, max 500); `totalRows` is the full count. Unknown report → `404`.

### `GET /reports/analytics/{report}.csv?from&to` - CSV download
- `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="coryd-<report>-<from>-to-<to>.csv"`.
- **Bounded:** at most **50,000** rows. `X-Report-Rows` = rows in the range; `X-Report-Truncated: true` when there were more (narrow the range).
- **Streamed:** trip-service writes rows from the database cursor straight into the response and ops-service passes the bytes through - neither holds the file.
- RFC 4180 quoting; a text cell starting with `= + - @` is prefixed with `'` so spreadsheets don't run it.

### Emailed reports (notification-service's daily 06:00 / weekly Mon 06:05 / monthly 1st 06:10)
- `POST /reports/emailed/{DAILY|WEEKLY|MONTHLY}/send?date=YYYY-MM-DD&to=a@x.ng&to=b@y.ng` - sends now for the period containing `date` (default: the current one), to `to` or the usual `REPORT_RECIPIENTS`. Audited (`REPORT_SENT`).
- `GET /reports/emailed/{period}/preview?date` - the same report as HTML (`text/html`), nothing sent.
- Bad period → `400`.

Recipients (US-16.3) are still the `REPORT_RECIPIENTS` env var.

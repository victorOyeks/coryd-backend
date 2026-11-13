# Ops Console: Performance at volume

Phase 7. Every list, search and count the console calls, measured against a database seeded to a realistic size,
with the slow ones fixed. Measured 2026-11-13 on a local stack (one Postgres 16 + PostGIS, Kafka, Redis in Docker; all
six services from jars), never on the dev server.

**What to know first:**
- Everything the console lists is paged or bounded on the server. Directories, queues and audit stay at a few tens of ms
  at any page; the money list, the six-month reports and the CSV downloads were the slow ones and are fixed.
- One real bug turned up along the way (section 4.1): batch id lookups of 200 ids were refused by Tomcat, so the
  rider/driver directory index silently lost phones, suspensions and join dates. That's fixed too.
- Numbers are from a shared, busy laptop (load average 15–40 during the runs), so absolute times are pessimistic and
  wobble about ±2× between runs. Before and after were run back to back on the same data (an A/B of the old and new jars),
  which is what the comparison rests on.

---

## 1. Dataset

Seeded directly in SQL, respecting each schema's constraints; the directory read models (`rider_index`, `driver_index`)
built through profile-service's own backfill (which calls trip, payment and identity for stats and contacts).

| What | Rows |
|---|---|
| Riders (`RIDER` role; 750 of them drivers too) | 20,755 |
| Drivers (70% approved, rest pending / in review / rejected / resubmitted; 53 suspended) | 3,010 |
| Driver documents (insurance + roadworthiness, expiries from 90 days ago to a year ahead) | 6,020 |
| Identity checks (riders and drivers, approved / pending / in review / rejected) | 20,015 |
| Route posts over ~6 months + 2 weeks ahead (4,829 cancelled, some stuck in progress) | 60,000 |
| Bookings (2–3 per trip; completed, no-show, cancelled by rider / driver, confirmed) | 150,000 |
| Booking status history | 290,468 |
| Cancellation log | 22,901 |
| Payments (incl. 6,000 failed attempts) / refunds / payouts (`driver_settlements`) | 156,000 / 18,072 / 41,600 |
| Earnings transactions / ops money requests | 122,358 / 2,083 |
| Ratings / SOS incidents / issue reports | 28,922 / 392 / 500 |
| Ops audit rows (trip + money + account + verification + notification) | 55,040 |
| Notification log | 400,050 |

## 2. Method

- An `OPS_ADMIN` token (ops role `SUPER_ADMIN`) signed with the local JWT secret.
- Each endpoint: two warm-up calls, then 15 timed calls (5 for reports and CSVs), sequential, through ops-service
  (`/ops/...`), so every figure includes ops-service's fan-out to the owning services. p50 / p95 of wall time.
- Postgres logged every statement over 80 ms during the runs; the slow ones were run again under `EXPLAIN ANALYZE`.
- Reports and CSVs use a six-month range (`from=2026-05-16&to=2026-11-13`).

## 3. Latency, before and after

**Bold** rows are the ones changed in this phase. Paths are under `/ops`.

| Endpoint | Path | Before p50 / p95 (ms) | After p50 / p95 (ms) |
|---|---|---|---|
| riders | `/riders?…` | 30 / 35 | 32 / 39 |
| riders sort=TRIPS desc | `/riders?…` | 29 / 40 | 31 / 43 |
| riders sort=TOTAL_SPENT | `/riders?…` | 37 / 61 | 32 / 69 |
| **riders status=SUSPENDED** | `/riders?…` | 23 / 112 | 20 / 46 |
| riders identity=PENDING | `/riders?…` | 26 / 55 | 20 / 34 |
| riders search name | `/riders?…` | 30 / 52 | 26 / 41 |
| **riders search phone frag** | `/riders?…` | 15 / 21 | 18 / 44 |
| riders page 400 | `/riders?…` | 51 / 66 | 66 / 90 |
| riders page 999 (past end) | `/riders?…` | 46 / 62 | 77 / 100 |
| riders/counts | `/riders/counts` | 27 / 40 | 27 / 30 |
| riders/counts search | `/riders/counts?…` | 31 / 41 | 33 / 63 |
| rider detail | `/riders/d939fced-aefa-dccb-d6d1-cb5811c1e4d2` | 51 / 77 | 57 / 130 |
| rider trips | `/riders/d939fced-aefa-dccb-d6d1-cb5811c1e4d2/trips?…` | 35 / 51 | 38 / 63 |
| drivers | `/drivers?…` | 17 / 19 | 17 / 84 |
| drivers sort=EARNINGS | `/drivers?…` | 16 / 19 | 25 / 34 |
| drivers search plate | `/drivers?…` | 17 / 34 | 21 / 57 |
| **drivers search phone frag** | `/drivers?…` | 12 / 16 | 17 / 24 |
| drivers page 140 | `/drivers?…` | 21 / 30 | 15 / 19 |
| drivers/counts | `/drivers/counts` | 22 / 84 | 14 / 16 |
| driver detail | `/drivers/a1b12157-6dfd-3d60-b6f8-df207356a4cb` | 47 / 94 | 35 / 42 |
| verification queue | `/drivers/verification/queue?…` | 45 / 128 | 39 / 82 |
| verification queue IN_REVIEW | `/drivers/verification/queue?…` | 9 / 13 | 11 / 15 |
| verification queue/counts | `/drivers/verification/queue/counts` | 9 / 11 | 16 / 73 |
| driver verification | `/drivers/a1b12157-6dfd-3d60-b6f8-df207356a4cb/verification` | 28 / 62 | 39 / 69 |
| identity queue | `/identity-verification/queue?…` | 29 / 44 | 33 / 45 |
| identity partial matches | `/identity-verification/partial-name-matches?…` | 21 / 63 | 10 / 12 |
| identity people | `/identity-verification/people?…` | 72 / 179 | 66 / 115 |
| identity people APPROVED | `/identity-verification/people?…` | 90 / 195 | 67 / 79 |
| identity people search | `/identity-verification/people?…` | 107 / 195 | 75 / 128 |
| identity people page 300 | `/identity-verification/people?…` | 91 / 127 | 90 / 143 |
| identity people/counts | `/identity-verification/people/counts` | 58 / 137 | 41 / 55 |
| document expiry | `/document-expiry?…` | 17 / 20 | 21 / 28 |
| document expiry EXPIRED | `/document-expiry?…` | 17 / 19 | 12 / 17 |
| document expiry/counts | `/document-expiry/counts` | 19 / 50 | 13 / 16 |
| trips | `/trips?…` | 139 / 282 | 82 / 179 |
| trips ACTIVE | `/trips?…` | 52 / 82 | 47 / 85 |
| trips CANCELLED | `/trips?…` | 44 / 70 | 56 / 119 |
| trips search | `/trips?…` | 143 / 267 | 121 / 206 |
| trips by rider | `/trips?…` | 38 / 94 | 32 / 63 |
| trips by driver | `/trips?…` | 40 / 55 | 34 / 141 |
| trips sort fare | `/trips?…` | 152 / 254 | 125 / 170 |
| trips page 2000 | `/trips?…` | 136 / 256 | 152 / 264 |
| trips/counts | `/trips/counts` | 73 / 91 | 66 / 139 |
| trips/counts search | `/trips/counts?…` | 225 / 322 | 201 / 320 |
| trips/live | `/trips/live` | 134 / 321 | 134 / 470 |
| trips/stuck | `/trips/stuck?…` | 71 / 161 | 41 / 115 |
| trips last-minute changes | `/trips/time-changes/last-minute` | 7 / 10 | 17 / 35 |
| trip detail | `/trips/2fdabc13-cdb0-128b-50d7-5d01c30712a1` | 49 / 60 | 43 / 59 |
| cancellations | `/cancellations?…` | 66 / 224 | 58 / 151 |
| cancellations fault=DRIVER | `/cancellations?…` | 74 / 103 | 59 / 172 |
| cancellations search | `/cancellations?…` | 61 / 247 | 84 / 150 |
| cancellations/counts | `/cancellations/counts` | 56 / 145 | 74 / 188 |
| **money transactions** | `/money/transactions?…` | 700 / 810 | 81 / 113 |
| **money tx PAYMENT** | `/money/transactions?…` | 388 / 563 | 73 / 146 |
| **money tx REFUND** | `/money/transactions?…` | 350 / 375 | 32 / 54 |
| **money tx by rider** | `/money/transactions?…` | 115 / 244 | 24 / 59 |
| **money tx amount sort** | `/money/transactions?…` | 539 / 798 | 169 / 358 |
| money tx reference | `/money/transactions?…` | 190 / 272 | 257 / 318 |
| **money tx page 500** | `/money/transactions?…` | 679 / 782 | 312 / 382 |
| **money transactions/counts** | `/money/transactions/counts` | 188 / 275 | 50 / 104 |
| money payouts | `/money/payouts?…` | 50 / 75 | 43 / 68 |
| money payouts FAILED | `/money/payouts?…` | 53 / 78 | 54 / 77 |
| money payouts/counts | `/money/payouts/counts` | 23 / 28 | 24 / 62 |
| money approvals | `/money/approvals?…` | 37 / 67 | 28 / 45 |
| money refund-failures | `/money/refund-failures?…` | 145 / 245 | 156 / 231 |
| **money finance summary** | `/money/finance/summary` | 195 / 270 | 173 / 255 |
| money pricing | `/money/pricing` | 51 / 60 | 94 / 130 |
| money audit | `/money/audit?…` | 8 / 9 | 11 / 12 |
| driver earnings | `/money/drivers/a1b12157-6dfd-3d60-b6f8-df207356a4cb/earnings` | 22 / 28 | 29 / 74 |
| sos | `/sos?…` | 42 / 55 | 53 / 110 |
| sos/counts | `/sos/counts` | 10 / 14 | 8 / 14 |
| sos safety-alerts | `/sos/safety-alerts?…` | 15 / 19 | 18 / 37 |
| sos detail | `/sos/f0515049-02da-dbad-ca3e-e29848b367e2` | 55 / 87 | 127 / 193 |
| reports | `/reports?…` | 31 / 34 | 39 / 52 |
| reports/counts | `/reports/counts` | 9 / 10 | 8 / 11 |
| report detail | `/reports/3f74582b-65f6-9868-26ef-6c927596bed7` | 21 / 24 | 30 / 64 |
| ratings | `/ratings?…` | 31 / 54 | 49 / 67 |
| ratings low stars | `/ratings?…` | 25 / 38 | 26 / 43 |
| ratings summary | `/ratings/summary/a1b12157-6dfd-3d60-b6f8-df207356a4cb` | 4 / 7 | 6 / 9 |
| dashboard | `/dashboard` | 1 / 2 | 2 / 8 |
| dashboard fresh | `/dashboard?…` | 207 / 222 | 150 / 218 |
| corridors | `/corridors` | 93 / 112 | 142 / 294 |
| metrics pilot | `/metrics/pilot` | 106 / 115 | 215 / 340 |
| system health | `/system/health` | 44 / 51 | 49 / 84 |
| **audit feed** | `/audit?…` | 23 / 29 | 17 / 22 |
| **audit feed search** | `/audit?…` | 45 / 53 | 18 / 27 |
| account history | `/users/d939fced-aefa-dccb-d6d1-cb5811c1e4d2/account-history?…` | 3 / 4 | 4 / 5 |
| team | `/team` | 5 / 9 | 6 / 10 |
| beta signups | `/beta-signups?…` | 3 / 3 | 3 / 4 |
| account deletions | `/account-deletions?…` | 3 / 3 | 3 / 4 |
| **analytics trips** | `/reports/analytics/trips?…` | 261 / 268 | 220 / 224 |
| analytics bookings | `/reports/analytics/bookings?…` | 129 / 138 | 129 / 137 |
| analytics cancellations | `/reports/analytics/cancellations?…` | 7 / 8 | 10 / 12 |
| **analytics driver-performance** | `/reports/analytics/driver-performance?…` | 1258 / 1363 | 215 / 289 |
| **analytics riders** | `/reports/analytics/riders?…` | 199 / 243 | 149 / 200 |
| **analytics corridors** | `/reports/analytics/corridors?…` | 538 / 895 | 307 / 469 |
| analytics revenue | `/reports/analytics/revenue?…` | 381 / 446 | 364 / 470 |
| analytics verification-funnel | `/reports/analytics/verification-funnel?…` | 17 / 19 | 9 / 11 |
| **csv bookings 6mo** | `/reports/analytics/bookings.csv?…` | 7854 / 9898 | 538 / 555 |
| **csv trips 6mo** | `/reports/analytics/trips.csv?…` | 5260 / 5577 | 408 / 493 |
| **csv driver-performance 6mo** | `/reports/analytics/driver-performance.csv?…` | 1971 / 2298 | 232 / 279 |
| **csv riders 6mo** | `/reports/analytics/riders.csv?…` | 1225 / 1906 | 195 / 303 |
| **audit export csv** | `/audit/export` | 3336 / 3474 | 1502 / 1889 |
| audit feed deep paging (40 cursors) | `/audit?…` | 43 / 75 | 34 / 51 |

Rows over 300 ms p95 after the fixes are covered in section 6. Endpoints this phase didn't touch move up and down between
runs with the machine's load (e.g. `metrics pilot`, `sos detail`). That's noise, not a change.

## 4. What was fixed

### 4.1 Batch lookups of 200 ids were refused (bug, all services)

Batch lookups send ids as repeated query parameters (`?userIds=a&userIds=b…`). Each UUID is about 45 characters, so 200
ids make a ~9 KB request line. Tomcat refuses anything over 8 KB with a bare `400` before CoRyd code runs. Callers treat a
failed lookup as "no details", so nothing failed loudly:
- profile-service's rider/driver index backfill and nightly rebuild ask identity for contacts 200 at a time. **Every
  rider's phone, suspension and join date came back empty**, so the Riders directory's phone search found nothing and its
  Suspended tab showed nobody.
- ops-service's name/phone lookups (a money page holds up to 100 rows, each with a rider and a driver) had the same
  ceiling.

`IdBatches` (common) now splits these into batches of 100 (~4.5 KB). profile's identity contacts client and ops-service's
`DownstreamCalls.batch` use it, and a failed batch only blanks its own ids. trip- and payment-service's batch lookups
send one search-results page or one trip's riders at a time, far below 200 ids, so they were left as they are.

### 4.2 Money → Transactions (payment-service)

Before: one `UNION ALL` of payments (joined to fare holds), refunds (joined to payments, holds, requests) and payouts,
then filtered, sorted and paged. Postgres can't push a sort or a limit through a union of joins, so every call read and
joined every payment, refund and payout:

```
Limit -> Sort (top-N) -> Append (rows=215,600)
  -> Hash Left Join -> Seq Scan payment_transactions (156,000) + Seq Scan fare_holds (150,000)
  -> Hash Right Join ... Seq Scan refunds, payment_transactions, fare_holds, ops_money_requests
  -> Seq Scan driver_settlements (41,600)                                    Execution: 291 ms
```

After: one select per type. Each type returns its own first `offset + size` rows in the list's order (read off an
index, stopping at the page), and the page is cut from those three small sets. That gives the same rows as sorting
everything, which the new test checks for every sort. Counts are per type, so joins they don't filter on drop out
(`count` over payments became a plain scan with no join).

```
page 0:   Index Scan Backward idx_payment_transactions_created -> Nested Loop -> fare_holds_booking_id_unique   (rows=21)
          Index Scan idx_refunds_occurred ... ; Index Scan idx_driver_settlements_occurred                 Execution: 1.4 ms
page 500: same plan, 10,020 rows per type                                                                  Execution: 138 ms
```

A rider filter also matches the raw payer column (`t.rider_id` / `r.user_id`), so it uses the rider index (116 ms →
indexed). New indexes: `coryd:160`–`162` (section 5).

### 4.3 Finance summary / revenue report (payment-service)

The summary sums paid, failed and debt-repayment charges by `updated_at` over a window, which was a parallel seq scan of
every payment. `idx_payment_transactions_status_updated` (`coryd:161`) makes each a bitmap index scan: 76 ms → 21 ms for
30 days.

### 4.4 Reports: driver performance and corridors (trip-service)

Both summed each trip's bookings with correlated subqueries, two or three per trip, which over six months was ~170,000
index probes:

```
Seq Scan route_posts (56,288) ... SubPlan 1/2/3 -> Bitmap Index Scan idx_bookings_route_post_id (loops=56,288)
Execution: 2,849 ms (driver performance), 1,292 ms (corridors)
```

Now one grouped `route_posts LEFT JOIN bookings` (the same shape the Trips report already used).

A preview ran each report twice (a count, then the rows), and a CSV counted first for its `X-Report-Rows` header. Now:
- reports that group and sort everything (trips, driver performance, riders, corridors) take the total from the same run,
  using `count(*) over ()`;
- bookings and cancellations come straight off an index in report order, so they keep the cheap separate count (a
  window there would force all 140,000 rows to be built for a 100-row preview: 354 ms vs ~1,000 ms).

### 4.5 CSV downloads (common)

`RequestResponseLoggingFilter` holds every response in a `ContentCachingResponseWrapper`, then masks and logs the whole
body before sending any of it. A six-month bookings CSV (10 MB) only began to download after 5–8 s, and the work happened
twice: once in trip-service and again in ops-service relaying it. CSV downloads (`*.csv`, `*/export`) now skip the filter,
as server-sent events already did. `LoggingInterceptor` still logs the request line and status. Bookings CSV: 9.9 s → 0.55 s
p95.

### 4.6 Audit feed (ops-service)

Each page asked identity, payment, profile and trip one after another. They're now asked at once (the shared `Parallel`
fan-out, which keeps the admin's request for the forwarded token). Feed page p95: 80 ms → 22 ms. A source that refuses
still fails the page with its own status.

## 5. Indexes added (payment-service, `017-ops-performance.sql`)

| Changeset | Index | For |
|---|---|---|
| `coryd:160` | `idx_refunds_occurred` on `refunds ((coalesce(refunded_at, created_at)) DESC, id DESC)` | Transactions list order, refunds |
| `coryd:160` | `idx_driver_settlements_occurred` on `driver_settlements ((coalesce(settlement_date, created_at)) DESC, id DESC)` | Transactions list order, payouts |
| `coryd:161` | `idx_payment_transactions_status_updated` on `payment_transactions (status, updated_at)` | Finance summary, revenue report |
| `coryd:162` | `idx_payment_transactions_amount`, `idx_driver_settlements_amount` | Transactions sorted by amount (either way, via incremental sort) |

Payments already had `created_at`, `rider_id` and `booking_id` indexes, and `ops_money_requests.refund_id` was already
indexed. No other service needed a new index: directories, queues, document expiry and audit already use theirs.
`coryd:163`–`169` are unused.

## 6. Checked and fine as they are

- **Deep paging and search on the directories** (`rider_index`, `driver_index`): page 400 of riders walks
  `idx_rider_index_joined` (offset 8,000: 10 ms in SQL); a phone fragment (`%0001234%`) and a name use the trigram indexes
  (`idx_rider_index_phone_trgm`, `idx_rider_index_name_trgm`: 13 ms / 4 ms); counts are index-only. Page sizes are capped
  server-side: asking for `size=5000` gets 50 rows (100 for money and the audit feed).
- **Identity people, verification queue, document expiry, SOS, reports, ratings, cancellations, trips list/detail:**
  20–150 ms p95 through ops-service, with no statement over 80 ms.
- **Audit feed deep paging** is keyset-based: 40 cursor pages deep, still ~35 ms per page.

## 7. Known limits

| Where | Now (p95, busy machine) | Why | What would fix it |
|---|---|---|---|
| Money → Transactions, `reference` contains-search | ~320 ms | `lower(reference) like '%x%'` reads every payment's reference (no trigram index in the `payment` schema; `pg_trgm` lives in `profile`) | `pg_trgm` in `payment` + a GIN index, or prefix-only search |
| Money → Transactions, deep pages (page 500) | ~380 ms | Offset paging reads `offset + size` rows per type | Keyset cursor (API change) |
| Money → Transactions, amount sort | ~360 ms | Fares are round numbers, so ties are large and incremental sort works through big groups | Acceptable; a `(amount, created_at, id)` index per direction if it matters |
| Reports → revenue (6 months) | ~470 ms | Five daily series over ~150,000 payments, run one after another | Grows with the range picked, not with history; the default 30-day summary is ~100 ms |
| Reports → corridors (6 months) | ~470 ms | Normalises every trip's labels (`regexp_replace`) and takes a median per corridor | A stored corridor key on `route_posts` |
| Audit export CSV (10,000 rows) | ~1.9 s | 80 internal calls of 500 rows; every internal response is cached, masked and logged whole by `RequestResponseLoggingFilter` | Cap the size of logged bodies (touches the masking path, so left for the data-masking work) |
| Trips search / counts with `search` | ~200–320 ms | `ILIKE '%x%'` on labels across 60,000 trips; grows with history | A trigram index in `trip` |
| Index backfill | — | Two refreshes of the same rider at once (backfill + an event) hit `rider_index_pkey`, and the backfill **stops** at the first error; the next start resumes | Upsert in `RiderIndexService` / `DriverIndexService`, or carry on past a failed batch |

## 8. Re-running it

The seed SQL, token minter and benchmark script are not in the repo (local-only data). To repeat: stand up the stack from
`docker-compose.yml` plus a PostGIS Postgres, pre-create the five schemas, start the services so Liquibase builds them,
seed, restart profile-service (its startup backfill builds the directory index), then time each endpoint above with an
`OPS_ADMIN` token.

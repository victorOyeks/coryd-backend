# Ops Console: Trips & Bookings

Sidebar: **Trips** (table, Live Trips, Stuck trips) and **Cancellations Log**.

A **trip** is one route a driver posted: one driver, one vehicle, one departure, every seat booked on it. A **booking** is one rider's seat(s) on a trip.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. No token: `401`. Another role: `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back `success: false` with a `message` you can show as-is.
- **Who's recorded:** every action is signed with the signed-in admin (id and name). You never send it.
- **Times** are ISO-8601 UTC. **Days** (`from`, `to`) are calendar days in **Africa/Lagos**, `YYYY-MM-DD`, both inclusive.
- **Paging:** `page` (0-based, default `0`), `size` (default `20`, max `50`). Paged `data` is `{ content, page, size, totalElements, totalPages, last }`.
- **References:** `tripRef` `T-XXXXXX`, driver `D-…`, rider `R-…`, booking `B-…` are the first six characters of the id, upper-case. Search accepts `T-…` and `D-…`.

---

## 0. The rules

| # | Rule | What the server does |
|---|---|---|
| 1 | **Every action needs a reason.** | Missing or blank `reason` → `400`, nothing happens. |
| 2 | **Every action is audited**: who, what, which trip or booking, the state before and after, the reason and the fault. | Written in the same transaction as the action. Shows on the trip's timeline as `OPS_ACTION`. |
| 3 | **Fault decides the refund**, under the same cancellation policy riders get. | `RIDER` → the late-cancellation fee is kept back (25% of the fare inside 2 hours of departure, none outside; never when the driver moved the trip far enough that the rider could cancel free) unless `waiveFee: true`. `DRIVER` / `PLATFORM` → full refund. |
| 4 | **A whole trip can't be the rider's fault.** | `fault: RIDER` on a trip cancel or resolve → `400`. Cancel the rider's booking instead. |
| 5 | **Finished is finished.** | Cancelling a completed or already-cancelled trip, or a seat on one → `400`. |
| 6 | **Only stuck trips are resolved.** | `resolve` on a trip that isn't stuck → `400` (it says when it will be). Use cancel for anything else. |
| 7 | **Refunds go through the normal path, once.** | Every paid seat that ends sends exactly one cancellation to payment-service, which refunds to the card. A repeated or concurrent action can't refund twice. |
| 8 | **Riders and drivers are told** by push, like any other cancellation. | See section 9. |

**Stuck** means: still `IN_PROGRESS` past its **expected arrival** (the driver's real start, or the planned departure if they never tapped Start, plus Google's drive time, or 60 minutes if there is none) **plus 3 hours**. The 3 hours is a server setting (`trip.stuck.grace-hours`), no app release needed.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Trips table: tabs, filters, sort, paging | `GET /trips` |
| Tab badges | `GET /trips/counts` |
| **View** one trip (passengers, payments, timeline) | `GET /trips/{routePostId}` |
| Live Trips map + cards | `GET /trips/live` |
| Stuck trips queue | `GET /trips/stuck` |
| **Cancel trip** | `POST /trips/{routePostId}/cancel` |
| **Resolve** a stuck trip (complete / cancel) | `POST /trips/{routePostId}/resolve` |
| **Cancel booking** | `POST /bookings/{bookingId}/cancel` |
| Cancellations Log + badges | `GET /cancellations`, `GET /cancellations/counts` |

After an action, reload the trip (`GET /trips/{routePostId}`): the action's response says what happened, the trip shows the result.

---

## 2. Trips table: `GET /trips`

| Param | Default | Values |
|---|---|---|
| `status` | none (All) | `ACTIVE` (published, full or in progress) · `COMPLETED` · `CANCELLED` |
| `routeStatus` | none | One or more of `PUBLISHED`, `FULL`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` (repeat the param). Narrows inside the tab; one outside it matches nothing. |
| `from`, `to` | none | Lagos days of the **departure**, inclusive |
| `driverId` | none | The driver's user id |
| `riderId` | none | Trips this rider **paid** for a seat on |
| `search` | none | Either end of the route, the start of the trip id, `T-2F2F53`, or a driver ref `D-B062D8` |
| `sort` | `DEPARTURE_TIME` | `DEPARTURE_TIME` · `POSTED_AT` · `FARE_PER_SEAT` · `STATUS` · `STARTED_AT` · `COMPLETED_AT` |
| `direction` | `DESC` | `ASC` · `DESC` |
| `page`, `size` | `0`, `20` | max size `50` |

Sorting and paging happen on the server, across every page. Total fare isn't sortable (it depends on who rode).

An unknown `status`, `routeStatus`, `sort` or a bad date → `400` with the allowed values.

```json
{
  "content": [
    {
      "routePostId": "2f2f5329-2468-4941-be33-e3a81dde7dee",
      "tripRef": "T-2F2F53",
      "status": "IN_PROGRESS",
      "group": "ACTIVE",
      "driver": { "id": "…", "ref": "D-B062D8", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "vehicle": { "id": "…", "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA" },
      "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
      "departureTime": "2026-10-03T15:00:00Z",
      "seatCapacity": 4, "seatsBooked": 3,
      "farePerSeat": 1200.00, "totalFare": 3600.00,
      "driverRating": 4.88, "driverRatingCount": 25,
      "startedAt": "…", "completedAt": null
    }
  ],
  "page": 0, "size": 20, "totalElements": 127, "totalPages": 7, "last": false
}
```

`seatsBooked` and `totalFare` count paid riders who rode or are still booked. Driver, vehicle or phone that can't be loaded come back `null`; the row still shows.

### Badges: `GET /trips/counts`

Same `from`, `to`, `driverId`, `riderId`, `search` as the table (not `status`), so the badges match the filters.

```json
{ "all": 127, "active": 35, "completed": 82, "cancelled": 10,
  "published": 20, "full": 6, "inProgress": 9, "stuck": 2 }
```

---

## 3. One trip: `GET /trips/{routePostId}`

`404` "Trip not found" for an unknown id.

```json
{
  "trip": { "…same as a table row…" },
  "originLat": 6.6, "originLng": 3.5, "destinationLat": 6.43, "destinationLng": 3.42,
  "routeNote": "via Third Mainland",
  "estimatedDurationMinutes": 40,
  "postedAt": "2026-10-02T09:00:00Z",
  "originalDepartureTime": null,
  "timeChangeCount": 0,
  "expectedArrivalAt": "2026-10-03T15:40:00Z",
  "stuck": false,
  "stuckSince": null,
  "location": { "available": true, "lat": 6.51, "lng": 3.47, "recordedAt": "…" },
  "lastSeen": { "available": true, "lat": 6.51, "lng": 3.47, "recordedAt": "…" },
  "locationAgeSeconds": 14,
  "cancellation": null,
  "actions": { "cancel": true, "resolve": false },
  "passengers": [
    {
      "bookingId": "b0000001-…", "bookingRef": "B-B00000",
      "riderId": "…", "name": "Amaka Obi", "phone": "08011112222", "photoUrl": null,
      "seatCount": 1, "fareTotal": 1200.00, "status": "IN_PROGRESS",
      "pickupLabel": "Ikorodu garage", "pickupLat": 6.61, "pickupLng": 3.51, "dropoffLabel": null,
      "confirmedAt": "…", "pickedUpAt": "…", "completedAt": null,
      "cancellable": true,
      "riderFaultFeeNow": 300.00,
      "payment": { "status": "PAID", "method": "PAYSTACK", "cardType": "visa", "cardLast4": "4081", "reference": "…",
                   "paidAt": "…", "amountPaid": 1200.00, "refunded": 0, "refundPending": 0, "netPaid": 1200.00 },
      "refund": { "state": "NONE", "expected": 0, "refunded": 0, "pending": 0 },
      "cancellation": null
    }
  ],
  "timeline": [
    { "at": "…", "type": "POSTED", "actor": "DRIVER", "actorId": "…", "actorName": "Emeka Okafor",
      "bookingId": null, "riderId": null, "riderName": null, "detail": "Ikorodu → Victoria Island, leaving Sat 3 Oct, 4:00 PM" },
    { "at": "…", "type": "BOOKED", "actor": "RIDER", "actorId": "…", "actorName": "Amaka Obi",
      "bookingId": "…", "riderId": "…", "riderName": "Amaka Obi", "detail": "Payment succeeded" }
  ]
}
```

- **`passengers`**: every rider who **paid**, including those who later cancelled or didn't show. Unpaid holds are never listed.
- **`cancellable`**: the **Cancel booking** button. **`riderFaultFeeNow`**: what the rider would lose if you cancelled now as `RIDER` fault (show it in the dialog); `0` when no fee applies.
- **`payment`**: payment-service's record; `null` if payment-service couldn't be reached (the page still loads).
- **`refund`** and **`cancellation`**: once the seat was cancelled, its Cancellations Log row (section 7) and where its refund is (section 7, `refund.state`).
- **`actions.cancel`**: **Cancel trip** is allowed (trip not finished). **`actions.resolve`**: it's stuck, show **Resolve**.
- **`location`** is the live pin, only while the trip is under way and the driver's app sent a point in the last 2 minutes. **`lastSeen`** is the driver's last point however old (kept 48 hours), `locationAgeSeconds` its age; `null` if they never sent one.
- **`cancellation`** (top level): the trip's own log row once it was cancelled as a whole.

### Timeline `type`s (oldest first)

| `type` | When | `actor` |
|---|---|---|
| `POSTED` | Driver posted the trip | `DRIVER` |
| `TIME_CHANGED` | Driver moved the departure (`detail` has from/to, riders booked, "last minute") | `DRIVER` |
| `BOOKED` | A rider paid | `RIDER` |
| `DRIVER_EN_ROUTE`, `DRIVER_ARRIVED` | Driver on the way to / at a pickup | `DRIVER` |
| `PICKED_UP` | Rider boarded | `DRIVER` |
| `NO_SHOW` | Marked a no-show (`detail` has where the driver was) | `DRIVER`, or `OPS` |
| `BOOKING_COMPLETED` | Rider dropped off and settled | `DRIVER` |
| `BOOKING_CANCELLED` | A paid seat ended: who, reason, fault, fee kept | `RIDER`, `DRIVER`, `SYSTEM` or `OPS` |
| `BOOKING_UPDATED` | Other seat changes, e.g. a manifest mark undone | `DRIVER` |
| `STARTED` | Driver tapped Start | `DRIVER` |
| `COMPLETED` | Trip completed (`OPS` with the reason when ops resolved it) | `DRIVER` or `OPS` |
| `CANCELLED` | The whole trip was cancelled: who, reason, fault | as above |
| `SOS` | Someone pressed SOS (`detail` has where) | `RIDER` or `DRIVER` |
| `SAFETY_CHECK` | Automatic safety check (`detail`: `STOPPED`/`OFF_ROUTE`/`SIGNAL_LOST` - `OPEN`/`ALL_OK`/`HELP`/`ESCALATED`) | `SYSTEM` |
| `OPS_ACTION` | An ops action (`detail`: action and reason) | `OPS` |

---

## 4. Live Trips: `GET /trips/live?search=`

Started trips plus those boarding (departing within 30 minutes), soonest departure first. Poll every 15-30 seconds.

```json
{
  "total": 9, "active": 7, "boarding": 2,
  "trips": [
    {
      "routePostId": "…", "tripRef": "T-0156AB", "phase": "ACTIVE",
      "originLabel": "Lekki Phase 1", "destinationLabel": "Victoria Island",
      "originLat": 6.44, "originLng": 3.47, "destinationLat": 6.43, "destinationLng": 3.42,
      "departureTime": "…", "startedAt": "…",
      "driver": { "id": "…", "ref": "D-…", "name": "Ibrahim Aliyu", "phone": "…", "photoUrl": "…" },
      "driverRating": 4.8, "driverRatingCount": 31,
      "seatCapacity": 4, "seatsBooked": 4,
      "elapsedMinutes": 18, "estimatedDurationMinutes": 40, "etaAt": "2026-10-03T07:56:00Z", "progress": 0.45,
      "location": { "available": false, "lat": null, "lng": null, "recordedAt": null },
      "lastSeen": { "available": false, "lat": 6.45, "lng": 3.46, "recordedAt": "…" },
      "locationAgeSeconds": 600,
      "locationStale": true,
      "overdue": false,
      "stuck": false
    }
  ]
}
```

- **Pin:** `location` while `available`; otherwise `lastSeen` greyed with "last seen N min ago" from `locationAgeSeconds`. `locationStale` is `true` once the last point is over 2 minutes old.
- **ETA:** `etaAt` = start + expected drive time. `overdue`: started and past `etaAt`. `stuck`: past the stuck threshold (rule 0) - link to the stuck queue.
- `phase`: `ACTIVE` started (teal), `BOARDING` departs within 30 minutes, not started (amber).

---

## 5. Stuck trips: `GET /trips/stuck?page=&size=`

Longest overdue first.

```json
{
  "content": [
    {
      "trip": { "…a table row…" },
      "expectedArrivalAt": "2026-11-13T04:00:00Z",
      "stuckSince": "2026-11-13T07:00:00Z",
      "minutesPastExpectedArrival": 260,
      "boarded": 2, "awaitingPickup": 1, "completed": 0,
      "lastSeen": { "available": false, "lat": 6.5, "lng": 3.4, "recordedAt": "…" },
      "locationAgeSeconds": 12000
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
}
```

`boarded`: picked up, still aboard. `awaitingPickup`: paid but never picked up. `completed`: already dropped off by the driver.

---

## 6. Actions

All three return what happened:

```json
{
  "action": "CANCEL_TRIP",
  "auditId": "…",
  "routePostId": "…",
  "bookingId": null,
  "statusBefore": "PUBLISHED",
  "statusAfter": "CANCELLED",
  "refunds": [ { "bookingId": "…", "riderId": "…", "fareTotal": 1200.00, "fee": 0, "refund": 1200.00 } ],
  "completed": [],
  "noShows": []
}
```

`refunds` lists each paid seat that will be refunded and how much. The refund itself is payment-service's: it reaches Paystack within seconds, the rider's card in hours to days. Track it in the log (`refund.state`).

### 6.1 Cancel a trip: `POST /trips/{routePostId}/cancel`

```json
{ "reason": "Driver phoned in sick", "fault": "DRIVER" }
```

| Field | Required | Values |
|---|---|---|
| `reason` | yes | up to 500 characters |
| `fault` | yes | `DRIVER` · `PLATFORM` (`RIDER` → `400`) |

Every seat is cancelled; every paid rider refunded in full. `message`: "Trip cancelled". `action`: `CANCEL_TRIP`.

| Error | When |
|---|---|
| `400` "reason: is required" / "fault: is required - DRIVER or PLATFORM" | missing |
| `400` "A whole trip can't be a rider's fault - …" | `fault: RIDER` |
| `400` "This trip is already completed - it can't be cancelled" | completed |
| `400` "This trip is already cancelled" | cancelled (including a second click) |
| `404` "Trip not found" | unknown id |

### 6.2 Cancel a booking: `POST /bookings/{bookingId}/cancel`

```json
{ "reason": "Rider called support - family emergency", "fault": "RIDER", "waiveFee": true }
```

| Field | Required | Values |
|---|---|---|
| `reason` | yes | up to 500 characters |
| `fault` | yes | `RIDER` · `DRIVER` · `PLATFORM` |
| `waiveFee` | no | `true` refunds a `RIDER`-fault cancellation in full. Ignored for other faults. |

- `RIDER` fault → seat becomes `CANCELLED_RIDER`; fee per rule 3 unless waived. `DRIVER` / `PLATFORM` → `CANCELLED_DRIVER`, full refund.
- The seat goes back on sale (the trip reopens if it was full).
- Allowed while the seat is `CONFIRMED`, `EN_ROUTE_TO_PICKUP`, `AT_PICKUP` or `IN_PROGRESS` (rider aboard). A seat still `HELD`/`RESERVED` (not paid) is just let go: `refunds` is empty.
- `message`: "Booking cancelled". `action`: `CANCEL_BOOKING`.

| Error | When |
|---|---|
| `400` "reason: is required" / "fault: is required - …" | missing |
| `400` "This trip is already completed - its bookings can't be cancelled" | trip completed or cancelled |
| `400` "This booking can't be cancelled - it's already COMPLETED" (or `NO_SHOW`, `CANCELLED_…`) | seat already finished, including a second click |
| `400` "This booking changed while you were cancelling it - reload and try again" | the rider or driver acted at the same moment |
| `404` "Booking not found" | unknown id |

### 6.3 Resolve a stuck trip: `POST /trips/{routePostId}/resolve`

```json
{ "outcome": "COMPLETED", "reason": "Driver confirmed by phone - forgot to tap Complete", "unboardedBookings": "REFUND" }
```
```json
{ "outcome": "CANCELLED", "reason": "Trip never ran - riders confirmed", "fault": "DRIVER" }
```

| Field | Required | Values |
|---|---|---|
| `outcome` | yes | `COMPLETED` · `CANCELLED` |
| `reason` | yes | up to 500 characters |
| `fault` | no | `CANCELLED` only: `DRIVER` · `PLATFORM` (default `PLATFORM`; `RIDER` → `400`) |
| `unboardedBookings` | no | `COMPLETED` only: `REFUND` (default) · `NO_SHOW` |

- **`COMPLETED`**: riders aboard are completed exactly like the driver's Complete Trip: the driver's earnings settle as normal and both sides get the "Trip completed" push. Riders never picked up are refunded in full (`REFUND`) or marked no-shows, the driver keeping the fare (`NO_SHOW`). Unpaid holds are let go. The trip ends `COMPLETED`. `action`: `RESOLVE_STUCK_COMPLETED`; `completed`, `refunds`, `noShows` list the seats.
- **`CANCELLED`**: same as 6.1. `action`: `RESOLVE_STUCK_CANCELLED`.

| Error | When |
|---|---|
| `400` "outcome: is required - COMPLETED or CANCELLED" / "reason: is required" | missing |
| `400` "Only a trip stuck in progress can be resolved - this one is COMPLETED" | not in progress (including a second click) |
| `400` "This trip isn't stuck yet - it counts as stuck 3h after its expected arrival (Fri 13 Nov, 4:00 PM). Cancel it instead if it must end now." | in progress, not stuck yet |
| `404` "Trip not found" | unknown id |

---

## 7. Cancellations Log: `GET /cancellations`

Every cancelled trip and every paid seat that ended: by the rider, the driver, the never-started sweep, a payment that arrived too late, a suspension, a closed account, or ops. Unpaid holds that lapse aren't cancellations and aren't here. Newest first.

| Param | Values |
|---|---|
| `source` | `RIDER` · `DRIVER` · `SYSTEM_EXPIRY` (driver never started; auto-cancelled 45 min after departure) · `SYSTEM` (payment landed after the seat was gone; refunded) · `OPS` · `SUSPENSION` · `ACCOUNT_DELETED` |
| `fault` | `DRIVER` · `RIDER` · `PLATFORM` |
| `kind` | `ROUTE` (whole trips) · `BOOKING` (every seat, a cancelled trip's seats included) |
| `from`, `to` | Lagos days of the **cancellation** |
| `driverId`, `riderId`, `routePostId` | exact ids. `routePostId` lists everything for that trip. |
| `search` | route labels, reason, start of the trip or booking id, `T-…` / `D-…` |
| `page`, `size` | |

**With no `kind` and no `routePostId`**, each cancellation is listed once: a whole trip as one `ROUTE` row (its seats folded in, money summed, `childBookingIds` listing them), and seats cancelled on their own as `BOOKING` rows. To expand a trip's row, call again with `routePostId`.

```json
{
  "content": [
    {
      "id": "…", "kind": "BOOKING",
      "routePostId": "…", "tripRef": "T-2F2F53", "bookingId": "…",
      "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
      "departureTime": "…", "cancelledAt": "…", "minutesBeforeDeparture": 45,
      "source": "OPS", "actorId": "…", "actorName": "Ada Ops",
      "reason": "Rider called support", "fault": "RIDER",
      "rider": { "id": "…", "ref": "R-A11CE0", "name": "Amaka Obi", "phone": "…", "photoUrl": null },
      "driver": { "id": "…", "ref": "D-D0C0FF", "name": "Emeka Okafor", "phone": "…", "photoUrl": "…" },
      "seatCount": 1, "fareTotal": 1200.00, "paid": true, "feeAmount": 300.00,
      "refund": { "state": "REFUNDED", "expected": 900.00, "refunded": 900.00, "pending": 0 },
      "bookingsAffected": 0, "childBookingIds": [],
      "routeStatusBefore": "PUBLISHED", "bookingStatusBefore": "CONFIRMED",
      "backfilled": false
    }
  ],
  "page": 0, "size": 20, "totalElements": 41, "totalPages": 3, "last": false
}
```

- **Who:** `source`, and `actorName` (the admin for `OPS`, the rider or driver for their own; `null` for the system).
- **Time before departure:** `minutesBeforeDeparture`, negative once departure had passed.
- **`ROUTE` rows:** `rider` is `null`; `seatCount`, `fareTotal`, `feeAmount`, `refund` are summed over `childBookingIds`; `bookingsAffected` counts them.
- **`refund.state`** (from payment-service): `NOT_PAID` · `NO_REFUND_DUE` (the fee kept the whole fare) · `NOT_STARTED` (payment-service hasn't refunded yet) · `PENDING` (Paystack hasn't accepted it; payment-service retries) · `PARTIAL` · `REFUNDED` (sent; reaches the card in hours to days) · `UNKNOWN` (payment-service unreachable; the row still shows).
- **`backfilled: true`:** recorded before this log existed and rebuilt from the trip data: no reason, and `source`/`fault`/fee are best guesses. Show a "reconstructed" hint.

### Badges: `GET /cancellations/counts`

Same params as the list except `source`.

```json
{ "all": 41, "rider": 20, "driver": 9, "systemExpiry": 5, "system": 1, "ops": 6, "suspension": 0, "accountDeleted": 0 }
```

---

## 8. Validation errors

- Missing or blank required fields: `400` `"<field>: is required…"`.
- Unknown enum value (`status`, `routeStatus`, `sort`, `direction`, `fault`, `outcome`, `source`, `kind`, `unboardedBookings`): `400` naming the allowed values.
- Bad date: `400`.
- A service behind the console unavailable: `502` "… is unavailable right now - try again shortly".

---

## 9. What the mobile app sees

| Action | Rider app | Driver app |
|---|---|---|
| **Cancel trip** (6.1, or resolve as `CANCELLED`) | Push "Booking cancelled": "CoRyd support cancelled this trip. You've been fully refunded." (no refund line if they hadn't paid). The booking reads `CANCELLED_DRIVER`. Then a "Refund issued" push with the amount once payment-service refunds. The trip leaves search and the group chat is closed. | Push "Route cancelled": "CoRyd support cancelled your route and N bookings were refunded." The route reads `CANCELLED`. If they'd been paid at booking time, the refunded share is added to what they owe (the usual "A trip you were paid for was refunded" push). |
| **Cancel booking**, `RIDER` fault | Push: "CoRyd support cancelled your booking. You'll be refunded, less the late-cancellation fee." (or "fully refunded" when no fee applied or it was waived). Booking reads `CANCELLED_RIDER`. Refund push follows. | Push "A seat opened up": "CoRyd support cancelled a booking on your route - the seat is available again." The manifest drops the rider; the trip is bookable again if it was full. |
| **Cancel booking**, `DRIVER`/`PLATFORM` fault | Same push with "You've been fully refunded." Booking reads `CANCELLED_DRIVER`. | Same "A seat opened up" push. |
| **Cancel booking** that wasn't paid yet | Nothing is pushed; the hold is gone (`CANCELLED_RIDER`) and the payment screen's hold ends. | Nothing - they never saw an unpaid hold. |
| **Resolve as `COMPLETED`** | Riders aboard: the normal "Trip completed - Rate your driver!" push; trip in history as `COMPLETED`, receipt available. Riders never picked up: refunded ("CoRyd support cancelled this trip…") or, with `NO_SHOW`, their booking reads `NO_SHOW` and nothing is pushed. | The normal "Trip completed" push per rider aboard; earnings settle the usual way. The route reads `COMPLETED` and leaves the active trip screen. |

The reason ops typed is **not** shown in the apps (it's in the audit log, the timeline and the Cancellations Log).

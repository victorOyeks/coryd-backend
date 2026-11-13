# Ops Console: Safety & Support

Sidebar: **Safety & SOS** (the SOS queue), **Reports & Disputes**, **Ratings**. Plus the console-wide **live stream** that pushes SOS and badge changes.

**Basics** (as for every console screen):
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** an **`OPS_ADMIN`** token, `Authorization: Bearer …`. No token: `401`. Another role: `403`. The one exception is `GET /stream`, which takes a stream token in the URL (section 5).
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back `success: false` with a `message` you can show as-is.
- **Who's recorded:** every action is signed with the signed-in admin (id and name). You never send it.
- **Times** are ISO-8601 UTC. **Days** (`from`, `to`) are calendar days in **Africa/Lagos**, `YYYY-MM-DD`, both inclusive.
- **Paging:** `page` (0-based, default `0`), `size` (default `20`, max `50`). Paged `data` is `{ content, page, size, totalElements, totalPages, last }`.
- **References:** `S-XXXXXX` (SOS), `T-` trip, `B-` booking, `D-` driver, `R-` rider - the first six characters of the id, upper-case. Reports have their own `ISS-00000000` reference.
- **People:** a driver or rider is `{ id, ref, name, phone, photoUrl }`; whoever raised or filed something is a **person** `{ id, ref, role, name, phone, photoUrl }` (`role` `RIDER`/`DRIVER`). An admin is `{ id, name }`. Any of these can be `null` (or have `null` fields) if a lookup failed - the row still shows.

---

## 0. The rules

| # | Rule | What the server does |
|---|---|---|
| 1 | **Every action is audited**: who, what, which SOS / report / rating, the state before and after, and the note or reason. | Written in the same transaction as the action. Actions on a trip also show on that trip's timeline (`GET /trips/{id}`) as `OPS_ACTION`. |
| 2 | **Finished is finished.** | Acknowledging, escalating or resolving a **resolved** SOS → `400`. Assigning, resolving or dismissing a **resolved or dismissed** report → `400`. Hiding a hidden rating / restoring a visible one → `400`. Notes can still be added to anything. |
| 3 | **One admin at a time.** | Two admins acknowledging the same SOS at the same moment: one wins, the other gets `400` "already being handled by …". |
| 4 | **Notes, outcomes and reasons are required where they matter.** | Missing/blank → `400` "`<field>`: is required…", nothing happens. |
| 5 | **An unacknowledged SOS keeps alerting ops.** | 5, 15 and 30 minutes after it was raised (server setting `SOS_REALERT_MINUTES`), each **once**, every ops phone and email gets "STILL UNACKNOWLEDGED" over the same path as the first alert (WhatsApp, SMS if WhatsApp didn't take it, and email). Acknowledging stops them. Not re-alerted once older than 12 h (`SOS_REALERT_MAX_AGE_HOURS`). |
| 6 | **Refunds and suspensions are done where they already live.** | A report never refunds or suspends anything itself: use `POST /bookings/{id}/cancel` or `POST /users/{id}/suspend`, then resolve the report with what they returned as `actionReference`. |

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| SOS queue: tabs, filters, paging | `GET /sos` |
| SOS tab badges | `GET /sos/counts` |
| One SOS (people, map, contacts, timeline) | `GET /sos/{id}` |
| **Acknowledge** / **Add note** / **Escalate** / **Resolve** | `POST /sos/{id}/acknowledge` · `/notes` · `/escalate` · `/resolve` |
| Every "Are you OK?" check (answered or not) | `GET /sos/safety-alerts` (unchanged) |
| Reports table + badges | `GET /reports`, `GET /reports/counts` |
| One report | `GET /reports/{id}` |
| **Assign** / **Note** / **Resolve** / **Dismiss** | `POST /reports/{id}/assign` · `/notes` · `/resolve` · `/dismiss` |
| Ratings list | `GET /ratings` |
| One person's rating breakdown | `GET /ratings/summary/{userId}` |
| **Hide** / **Restore** a rating | `POST /ratings/{id}/hide` · `/restore` |
| Live updates (alert banner, badges) | `POST /stream/token`, then `GET /stream?token=…` |

After an action, the response carries the item as it now stands; reload the detail for the full timeline.

---

## 2. Safety & SOS

### 2.1 What's in the queue

One queue, two **kinds**:

- **`SOS`** - a rider or driver pressed SOS in the app.
- **`SAFETY_CHECK`** - the trip looked wrong (a long stop away from any pickup, far off the route, or the driver's phone went quiet), everyone on board was asked "Are you OK?", and **nobody answered in time** (3 minutes). If anyone answers "I need help" it's a real `SOS` straight away instead.

Both are worked the same way. **Why one queue:** ops has one place to watch and one alert banner; an unanswered check is exactly as urgent as a press of the button (the person may be unable to press it), and needs the same acknowledge → call → resolve trail.

**Status** (the tabs):

| `status` | Meaning |
|---|---|
| `OPEN` | Nobody in ops has picked it up. Re-alerts keep going out (rule 5). |
| `ACKNOWLEDGED` | An admin claimed it (`acknowledgedBy`). |
| `ESCALATED` | An emergency service or contact was called (`escalation`). |
| `RESOLVED` | Closed with an `outcome`. |

Escalating or resolving an `OPEN` incident also claims it for you (`acknowledgedBy` is set if it wasn't).

> SOS raised **before** this release are in the queue as `OPEN` (nobody ever handled them) but are never re-alerted. Resolve them with `OTHER` once checked.

### 2.2 `GET /sos`

| Param | Values |
|---|---|
| `status` | `OPEN` · `ACKNOWLEDGED` · `ESCALATED` · `RESOLVED` (omit for All) |
| `kind` | `SOS` · `SAFETY_CHECK` |
| `from`, `to` | Lagos days it was **raised** |
| `routePostId` | one trip |
| `riderId` | SOS this rider raised, on their booking, or a driver's SOS on a trip they'd paid a seat on |
| `driverId` | SOS on this driver's trips (whoever raised it) |
| `page`, `size` | |

Newest first.

```json
{
  "content": [
    {
      "id": "5a5a5a5a-…", "ref": "S-5A5A5A",
      "kind": "SOS", "status": "OPEN",
      "raisedAt": "2026-11-13T10:00:00Z", "minutesOpen": 7,
      "raisedBy": { "id": "…", "ref": "R-A11CE0", "role": "RIDER", "name": "Amaka Obi", "phone": "08011112222", "photoUrl": null },
      "safetyCheckKind": null, "safetyAlertId": null,
      "routePostId": "…", "tripRef": "T-7A1B00", "bookingId": "…", "bookingRef": "B-B00B00",
      "originLabel": "Ikorodu", "destinationLabel": "Victoria Island", "routeStatus": "IN_PROGRESS", "departureTime": "…",
      "driver": { "id": "…", "ref": "D-D0C0FF", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "rider": { "id": "…", "ref": "R-A11CE0", "name": "Amaka Obi", "phone": "08011112222", "photoUrl": null },
      "vehicle": { "id": "…", "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA" },
      "latitude": 6.5, "longitude": 3.4,
      "opsNotifiedAt": "2026-11-13T10:00:01Z",
      "acknowledgedAt": null, "acknowledgedBy": null,
      "escalation": null,
      "resolution": null,
      "realertsSent": 1
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
}
```

- **`raisedBy`**: `null` for a `SAFETY_CHECK` (the system raised it). `rider` is the booking's rider for a rider's SOS, `null` for a driver's SOS on the route.
- **`safetyCheckKind`** (`SAFETY_CHECK` only): `STOPPED` · `OFF_ROUTE` · `SIGNAL_LOST`.
- **`latitude`/`longitude`**: where it was pressed (or where the car was when the check was raised); may be `null`. The **live** position is in the detail.
- **`minutesOpen`**: since it was raised; `null` once resolved.
- **`escalation`**: `{ recordedAt, target, contact, contactedAt, reference }` - the latest call recorded.
- **`resolution`**: `{ at, by: {id,name}, outcome, note }`.
- **`realertsSent`**: "still unacknowledged" re-alerts already sent to ops.

**Badges: `GET /sos/counts`** - same params as the list except `status`:

```json
{ "all": 12, "open": 1, "acknowledged": 1, "escalated": 0, "resolved": 10 }
```

### 2.3 One incident: `GET /sos/{id}`

`404` "SOS not found".

```json
{
  "incident": { "…a list row…" },
  "location": {
    "live":     { "available": true,  "lat": 6.51, "lng": 3.47, "recordedAt": "…" },
    "lastSeen": { "available": true,  "lat": 6.51, "lng": 3.47, "recordedAt": "…" },
    "ageSeconds": 14
  },
  "ridersOnTrip": [ { "id": "…", "ref": "R-…", "name": "…", "phone": "…", "photoUrl": null } ],
  "contactsNotified": [
    { "name": "Mum", "phone": "08011110000",
      "deliveries": [ { "at": "…", "channel": "WHATSAPP", "delivered": false, "errorCode": "HTTP_500" },
                      { "at": "…", "channel": "SMS", "delivered": true, "errorCode": null } ] }
  ],
  "opsAlerts": [
    { "at": "…", "eventType": "SOS_OPS_ALERT", "channel": "SMS", "recipient": "0803…", "delivered": true, "errorCode": null },
    { "at": "…", "eventType": "SOS_OPS_REALERT", "channel": "EMAIL", "recipient": "ops@coryd.ng", "delivered": true, "errorCode": null }
  ],
  "safetyCheck": null,
  "tripSafetyChecks": [
    { "alertId": "…", "kind": "STOPPED", "status": "HELP", "raisedAt": "…", "escalatedAt": null, "latitude": 6.5, "longitude": 3.4,
      "answers": [ { "userId": "…", "role": "DRIVER", "name": "Emeka Okafor", "phone": "…", "answer": "WAITING" },
                   { "userId": "…", "role": "RIDER", "name": "Amaka Obi", "phone": "…", "answer": "HELP" } ] }
  ],
  "timeline": [
    { "at": "…", "type": "SAFETY_CHECK", "actor": "SYSTEM", "actorId": null, "actorName": null, "detail": "STOPPED - HELP (Emeka Okafor: WAITING, Amaka Obi: HELP)" },
    { "at": "…", "type": "SOS_RAISED", "actor": "RIDER", "actorId": "…", "actorName": "Amaka Obi", "detail": "At 6.5,3.4" },
    { "at": "…", "type": "REALERT", "actor": "SYSTEM", "detail": "Still unacknowledged after 5 min - ops alerted again" },
    { "at": "…", "type": "OPS_ACKNOWLEDGED", "actor": "OPS", "actorId": "…", "actorName": "Ada Ops", "detail": "Acknowledged" }
  ],
  "actions": [ { "id": "…", "at": "…", "by": { "id": "…", "name": "Ada Ops" }, "action": "SOS_ACKNOWLEDGED", "note": "Acknowledged",
                 "before": "{\"status\":\"OPEN\"}", "after": "{\"status\":\"ACKNOWLEDGED\"}" } ],
  "allowed": { "acknowledge": true, "note": true, "escalate": true, "resolve": true },
  "trip": { "…exactly GET /trips/{routePostId}: trip, passengers with phones and payments, full trip timeline…" }
}
```

- **Map:** `location.live` while the trip is under way and the driver's app sent a point in the last 2 minutes; otherwise `lastSeen` greyed with "last seen N min ago" from `ageSeconds`. `location` and `trip` are `null` if the trip couldn't be loaded (the rest still shows).
- **Call buttons:** `incident.raisedBy.phone`, `incident.driver.phone`, `ridersOnTrip[].phone`.
- **`contactsNotified`**: the emergency contacts of whoever pressed SOS, as texted at the time. `deliveries` is each attempt: `WHATSAPP` first, `SMS` when WhatsApp was off or refused (`delivered: false` on the WhatsApp row). `deliveries` is `null` if the delivery records couldn't be read, `[]` if none were found. Empty `contactsNotified`: they had no emergency contact (the app told them so).
- **`opsAlerts`**: the first alert to ops (`SOS_OPS_ALERT`, or `SAFETY_ESCALATED` for a safety check) and each re-alert (`SOS_OPS_REALERT`), one row per phone / address and channel. `null` if unreadable. Matched by time window (the 15 minutes after it was raised, an hour once re-alerts went out): two SOS raised within that window may show each other's ops alerts.
- **`safetyCheck`** (`SAFETY_CHECK` only): the unanswered check and who said what. **`tripSafetyChecks`**: every check raised on that trip.
- **`timeline` `type`s**: `SOS_RAISED` · `SAFETY_CHECK_UNANSWERED` (a `SAFETY_CHECK` incident's own start) · `SAFETY_CHECK` · `SAFETY_CHECK_ESCALATED` · `REALERT` · `OPS_ACKNOWLEDGED` · `OPS_NOTE` · `OPS_ESCALATED` · `OPS_RESOLVED`. Oldest first.
- **`allowed`**: which buttons to show. `acknowledge` only while `OPEN`; `escalate`/`resolve` until resolved; `note` always.

### 2.4 Actions

All return what happened, with the incident as it now stands:

```json
{ "action": "SOS_ACKNOWLEDGED", "auditId": "…", "statusBefore": "OPEN", "statusAfter": "ACKNOWLEDGED", "incident": { "…a list row…" } }
```

**`POST /sos/{id}/acknowledge`** - claims it. Body optional: `{ "note": "Calling the rider now" }` (up to 1000). `message`: "SOS acknowledged".

**`POST /sos/{id}/notes`** - `{ "note": "Rider not answering - trying driver" }` (required, up to 1000). Any status. `message`: "Note added".

**`POST /sos/{id}/escalate`** - record who ops called. Can be repeated (e.g. police, then an ambulance); the incident shows the latest, the timeline every one. `message`: "Escalation recorded".

```json
{ "target": "POLICE", "contact": "Ikeja division, Sgt Bello", "contactedAt": "2026-11-13T10:04:00Z", "reference": "IKJ-2291", "note": "Car seen on Ikorodu Rd" }
```

| Field | Required | Values |
|---|---|---|
| `target` | yes | `POLICE` · `AMBULANCE` · `FIRE_SERVICE` · `EMERGENCY_LINE` (112/767) · `ROAD_SAFETY` (FRSC/LASTMA) · `EMERGENCY_CONTACT` (the person's own contact) · `OTHER` |
| `contact` | no | who you spoke to, up to 200 |
| `contactedAt` | no | when you called them; default now. Not in the future, not before the SOS. |
| `reference` | no | their incident / case number, up to 100 |
| `note` | no | up to 1000 |

**`POST /sos/{id}/resolve`** - `message`: "SOS resolved".

```json
{ "outcome": "SAFE", "note": "Rider confirmed by phone - car broke down, all fine" }
```

| Field | Required | Values |
|---|---|---|
| `outcome` | yes | `SAFE` · `FALSE_ALARM` · `ESCALATED_TO_AUTHORITIES` · `OTHER` |
| `note` | yes | up to 1000 |

| Error | When |
|---|---|
| `400` "note: is required" / "outcome: is required - SAFE, …" / "target: is required - …" | missing |
| `400` "This SOS is already being handled by Ada Ops (acknowledged …)" | acknowledge on `ACKNOWLEDGED` / `ESCALATED` (incl. the other admin's simultaneous click) |
| `400` "This SOS is already resolved - it can't be acknowledged" (… escalated) | acknowledge / escalate a resolved SOS |
| `400` "This SOS is already resolved (SAFE by Bola)" | resolve twice |
| `400` "contactedAt: can't be in the future" / "is before the SOS was raised" | |
| `404` "SOS not found" | |

`GET /sos/safety-alerts` is unchanged: every check ever raised (answered or not), with its answers.

---

## 3. Reports & disputes

Reports are what riders and drivers send from **Report an issue** (in a trip, or from their account with no trip).

| `status` | Meaning |
|---|---|
| `OPEN` | Nobody has it yet. |
| `IN_PROGRESS` | Assigned to an admin. |
| `RESOLVED` | Closed with an `outcome`; the reporter was told. |
| `DISMISSED` | Not a real problem (duplicate, test, nothing to act on). |

### 3.1 `GET /reports`

| Param | Values |
|---|---|
| `status` | `OPEN` · `IN_PROGRESS` · `RESOLVED` · `DISMISSED` |
| `category` | `SAFETY` · `PAYMENT` · `DRIVER_CONDUCT` · `RIDER_CONDUCT` · `VEHICLE_CONDITION` · `ROUTE_ISSUE` · `OTHER` |
| `reporterRole` | `RIDER` · `DRIVER` |
| `from`, `to` | Lagos days it was filed |
| `routePostId` | reports on one trip |
| `assignedTo` | an admin's user id ("Mine") |
| `userId` | reports filed by this person or about a trip they were on |
| `search` | the `ISS-…` reference or words in the description |

Newest first.

```json
{
  "content": [
    {
      "id": "…", "referenceNumber": "ISS-00000001", "status": "OPEN", "category": "DRIVER_CONDUCT",
      "description": "Driver was rude and drove dangerously",
      "reporter": { "id": "…", "ref": "R-A11CE0", "role": "RIDER", "name": "Amaka Obi", "phone": "08011112222", "photoUrl": null },
      "bookingId": "…", "bookingRef": "B-B00B00", "routePostId": "…", "tripRef": "T-7A1B00",
      "originLabel": "Ikorodu", "destinationLabel": "Victoria Island", "departureTime": "…",
      "driver": { "…" }, "rider": { "…" },
      "createdAt": "…",
      "assignedTo": null, "assignedAt": null,
      "outcome": null, "messageToReporter": null, "actionType": null, "actionReference": null,
      "resolvedAt": null, "resolvedBy": null
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
}
```

- A report filed from the account screen has no trip: `bookingId`, `routePostId`, `driver`, `rider` are `null`.
- `reporter.role` is the side of the trip they were on. Reports filed before this release with no trip show as `RIDER`.

**`GET /reports/counts`** - same params except `status`: `{ "all": 9, "open": 3, "inProgress": 2, "resolved": 3, "dismissed": 1 }`.

### 3.2 One report: `GET /reports/{id}`

`404` "Report not found".

```json
{
  "report": { "…a list row…" },
  "booking": { "bookingId": "…", "bookingRef": "B-…", "status": "COMPLETED", "seatCount": 1, "fareTotal": 1200.00,
               "pickupLabel": "…", "dropoffLabel": null, "confirmedAt": "…" },
  "attachments": [],
  "priorReports": [ { "report": { "…a list row…" }, "involves": ["<driver id>"] } ],
  "priorReportCounts": { "<reporter id>": 0, "<rider id>": 0, "<driver id>": 3 },
  "actions": [ { "id": "…", "at": "…", "by": { "id": "…", "name": "Ada Ops" }, "action": "REPORT_ASSIGNED", "note": "Assigned to Ada Ops", "before": "…", "after": "…" } ],
  "relatedActions": [
    { "type": "CANCEL_BOOKING_WITH_REFUND", "method": "POST", "path": "/bookings/<bookingId>/cancel", "referenceAs": "BOOKING_CANCELLATION", "referenceHint": "the auditId it returns" },
    { "type": "SUSPEND_DRIVER", "method": "POST", "path": "/users/<driverId>/suspend", "referenceAs": "SUSPENSION", "referenceHint": "<driverId>" },
    { "type": "SUSPEND_RIDER", "method": "POST", "path": "/users/<riderId>/suspend", "referenceAs": "SUSPENSION", "referenceHint": "<riderId>" }
  ],
  "allowed": { "assign": true, "note": true, "resolve": true, "dismiss": true }
}
```

- **`priorReports`**: up to 20 other reports filed by, or about a trip involving, the reporter, the rider or the driver - newest first. `involves` says which of this report's people each one concerns; `priorReportCounts` totals them per person (show "3 earlier reports on this driver").
- **`attachments`**: always `[]` today - the apps don't send photos with a report yet.
- **`actions`**: assignments, internal notes, the resolution - oldest first. Notes are `REPORT_NOTE`.
- **`relatedActions`**: the existing endpoints to act from the report (section 0, rule 6). Do the action there, then resolve with `actionType` = `referenceAs` and `actionReference` = what it returned (for a suspension, the suspended user's id).

### 3.3 Actions

All return `{ "action", "auditId", "statusBefore", "statusAfter", "report": {…a list row…} }`.

**`POST /reports/{id}/assign`** - no body assigns it to you. To another admin: `{ "assigneeId": "…", "assigneeName": "Bola", "note": "…" }`. Reassigning is allowed. `OPEN` → `IN_PROGRESS`. `message`: "Report assigned".

**`POST /reports/{id}/notes`** - `{ "note": "Called the driver - denies it" }`. Internal: the reporter never sees it. Any status. `message`: "Note added".

**`POST /reports/{id}/resolve`** - `message`: "Report resolved".

```json
{ "outcome": "SUSPENDED", "note": "Third conduct report this month",
  "messageToReporter": "Thanks for telling us. We've taken action on this driver's account.",
  "actionType": "SUSPENSION", "actionReference": "d0c0ffee-1111-…" }
```

| Field | Required | Values |
|---|---|---|
| `outcome` | yes | `NO_ACTION` · `WARNING` · `SUSPENDED` · `REFUND_ISSUED` · `OTHER` |
| `note` | yes | internal, up to 1000 |
| `messageToReporter` | yes | what the reporter reads, up to 1000 |
| `actionType` | with `actionReference` | `BOOKING_CANCELLATION` · `REFUND` · `SUSPENSION` · `OTHER` |
| `actionReference` | no | up to 100 - the audit id / refund id / user id from the action you took |

**`POST /reports/{id}/dismiss`** - `{ "note": "Duplicate of ISS-00000012", "messageToReporter": "…optional…" }`. The reporter is told only if you write to them. `message`: "Report dismissed".

| Error | When |
|---|---|
| `400` "note: is required" / "outcome: is required - …" / "messageToReporter: is required - the reporter reads it" | missing |
| `400` "actionType: is required with an actionReference - …" | reference without a type |
| `400` "This report is already resolved - it can't be resolved" (… dismissed / assigned) | second click, or acting on a closed report |
| `400` "reporterRole: must be RIDER or DRIVER" / unknown `status`, `category` / bad date | |
| `404` "Report not found" | |

---

## 4. Ratings

### 4.1 `GET /ratings`

| Param | Values |
|---|---|
| `maxStars` | 1-5 - "low ratings" is `maxStars=2` |
| `minStars` | 1-5 |
| `withComment` | `true` - only ratings with a comment |
| `hidden` | `true` hidden only · `false` visible only · omit for both |
| `direction` | `RIDER_TO_DRIVER` (riders rating drivers) · `DRIVER_TO_RIDER` |
| `rateeId`, `raterId` | one person's ratings received / given |
| `from`, `to` | Lagos days it was given |

Newest first. The starting rating every new profile is seeded with isn't listed.

```json
{
  "content": [
    { "id": "…", "bookingId": "…", "bookingRef": "B-…", "routePostId": "…", "tripRef": "T-…",
      "direction": "RIDER_TO_DRIVER", "stars": 1, "comment": "Rude and you are a thief", "createdAt": "…",
      "rater": { "id": "…", "ref": "R-…", "role": "RIDER", "name": "…", "phone": "…", "photoUrl": null },
      "ratee": { "id": "…", "ref": "D-…", "role": "DRIVER", "name": "…", "phone": "…", "photoUrl": "…" },
      "hidden": false, "hiddenAt": null, "hiddenBy": null, "hiddenReason": null }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
}
```

### 4.2 `GET /ratings/summary/{userId}?direction=`

Visible ratings only (hidden counted apart):

```json
{ "userId": "…", "ratingAvg": 4.67, "ratingCount": 3, "hiddenCount": 1,
  "distribution": { "1": 0, "2": 0, "3": 0, "4": 1, "5": 2 } }
```

### 4.3 Hide and restore

**`POST /ratings/{id}/hide`** · **`POST /ratings/{id}/restore`** - `{ "reason": "Abusive language" }` (required, up to 500). `message`: "Rating hidden" / "Rating restored".

```json
{ "action": "RATING_HIDDEN", "auditId": "…", "rating": { "…a list row, hidden: true…" },
  "rateeSummary": { "userId": "…", "ratingAvg": 5.0, "ratingCount": 1, "hiddenCount": 1, "distribution": { "…" } } }
```

A **hidden** rating stays on record and in this list, but counts in **no average anywhere** - search results, the trip and booking screens, receipts, the driver/rider directories. The rated person's stats are re-sent to the directories straight away.

| Error | When |
|---|---|
| `400` "reason: is required" | missing |
| `400` "This rating is already hidden (by Ada Ops)" / "This rating isn't hidden - there's nothing to restore" | |
| `400` "maxStars / minStars: must be 1 to 5" | |
| `404` "Rating not found" | |

---

## 5. Live updates (Server-Sent Events)

SOS must be pushed, not polled. The console keeps one `EventSource` open while signed in.

### 5.1 Connecting

A browser's `EventSource` can't send an `Authorization` header, so:

1. **`POST /stream/token`** (normal `OPS_ADMIN` bearer) →
   ```json
   { "token": "eyJzdWIi….Xk2Q", "expiresAt": "2026-11-13T10:01:00Z" }
   ```
   The token is good for **60 seconds** and only opens a stream - it isn't an access token, and an access token doesn't open a stream.
2. **`GET /stream?token=<token>`** with `EventSource`. The token only has to be valid when the stream opens.

```js
async function openStream(lastEventId) {
  const { data } = await api.post('/ops/stream/token');
  const es = new EventSource(`/ops/stream?token=${encodeURIComponent(data.token)}`
      + (lastEventId ? `&lastEventId=${encodeURIComponent(lastEventId)}` : ''));
  let last = lastEventId;
  const track = (handler) => (e) => { if (e.lastEventId) last = e.lastEventId; handler(JSON.parse(e.data)); };
  es.addEventListener('hello', track(h => setBadges(h.counts)));
  es.addEventListener('sos.raised', track(showSosBanner));          // banner + sound, refetch GET /sos and /sos/counts
  es.addEventListener('sos.updated', track(refreshSos));            // refetch the row / open drawer
  es.addEventListener('safety.escalated', track(refreshSos));
  es.addEventListener('counts', track(setBadges));
  es.addEventListener('resync', track(() => reloadAllLists()));
  es.addEventListener('bye', () => { es.close(); openStream(last); });
  es.onerror = () => { if (es.readyState === EventSource.CLOSED) setTimeout(() => openStream(last), 2000); };
  return es;
}
```

- **Reconnects:** on a dropped connection `EventSource` reconnects by itself and sends `Last-Event-ID` - but with the same (by then expired) token, which gets `401` and closes it (`readyState` `CLOSED`). Then get a new token and open a new `EventSource`, passing the last id you saw as `lastEventId` (the server takes the `Last-Event-ID` header or the `lastEventId` param).
- **Replay:** events you missed since `lastEventId` are sent again, in order, after `hello`. If they can't be (the server restarted, another instance served you, or it was too long ago) you get **`resync`**: reload the SOS queue, reports and badges.
- **Length:** a stream is ended after **30 minutes** with a `bye` event; open a new one with a new token. An admin who is suspended or signed out everywhere can't get a new token.
- **Heartbeat:** a comment line (`:hb …`) every **15 seconds**. Nothing to handle - it keeps proxies from closing the connection. No event and no heartbeat for 45 s: treat the stream as dead and reopen it.
- **Proxy:** if the console proxies `/ops/**`, the proxy must stream `text/event-stream` responses (no buffering, no compression). The server sends `X-Accel-Buffering: no` and `Cache-Control: no-cache, no-transform`.

| Response | When |
|---|---|
| `200` `text/event-stream` | open |
| `401` (empty body, reason in `X-Stream-Error`) | no token, a forged/malformed token, an expired token |

### 5.2 Events

Every event's `data` is JSON. Events from Kafka carry an `id`; `hello`, `resync` and `bye` don't.

| `event` | When | `data` |
|---|---|---|
| `hello` | first on every connection | `{ connectionId, serverTime, expiresAt, heartbeatSeconds, lastEventId, replayed, counts }` - `counts` as in `counts` below, or `null` if the server hasn't had one yet |
| `sos.raised` | someone pressed SOS | `{ incidentId, kind: "SOS", status: "OPEN", triggeredByRole, bookingId, routePostId, routeSummary, driverName, driverPhone, riderPhone, vehiclePlate, latitude, longitude, raisedAt }` |
| `sos.raised` | an "Are you OK?" check went unanswered and joined the queue | `{ incidentId, kind: "SAFETY_CHECK", action: "RAISED", status: "OPEN", routePostId, safetyAlertId, at, … }` |
| `sos.updated` | an admin acted, or a re-alert went out | `{ incidentId, kind, action, status, routePostId, bookingId, safetyAlertId, actorId, actorName, outcome, minutesOpen, at }` - `action` `ACKNOWLEDGED` · `NOTE` · `ESCALATED` · `RESOLVED` · `REALERTED` (`minutesOpen` set, no actor) |
| `safety.escalated` | nobody answered "Are you OK?" (arrives alongside the `SAFETY_CHECK` `sos.raised`) | `{ alertId, routePostId, kind, routeSummary, driverName, driverPhone, vehiclePlate, riderPhones, latitude, longitude, unanswered, notAnswered, answeredOk }` |
| `counts` | a badge changed (checked every 30 s, at once after any SOS or report change, and at least every 5 min) | `{ sos: { open, acknowledged, escalated }, stuckTrips, reports: { open, inProgress }, computedAt }` |
| `resync` | missed events can't be replayed | `{ reason }` |
| `bye` | the stream reached its 30 minutes | `{ reason: "EXPIRED", message }` |

**US-11.1 banner:** show it on `sos.raised` and keep it until `GET /sos/counts` shows `open: 0` (or `sos.updated` with `ACKNOWLEDGED` for that incident). `sos.raised` carries enough to show who / where / phones before the detail loads.

---

## 6. What the mobile app sees

| Action | Rider / driver app |
|---|---|
| **SOS acknowledge / note / escalate / resolve** | Nothing. The person who pressed SOS got "Emergency alert sent" when they pressed it, and their emergency contacts were texted then; ops calls them by phone. Ops notes are never shown in the apps. |
| **An unacknowledged SOS re-alerting** | Nothing in the apps. Ops phones get WhatsApp (or SMS) "CoRyd SOS STILL UNACKNOWLEDGED after 5 min: …", ops addresses an email. |
| **An unanswered safety check joining the queue** | Nothing new - everyone on board already got the "Are you OK?" push; answering it after this still works and still shows in the detail. |
| **Report: assign, note** | Nothing. The report's status isn't shown in the apps. |
| **Report: resolve** | The reporter gets a push and a feed entry: title "We've looked into report ISS-00000001", body = `messageToReporter` (event `ISSUE_REPORT_UPDATED`, the booking id as reference if there was a trip). The other party is **not** told about the report. Any refund or suspension reaches them through that action's own notifications. |
| **Report: dismiss** | Only if `messageToReporter` was given: push "Update on report ISS-…" with it. Otherwise nothing. |
| **Rating hide / restore** | Nobody is told. The rated person's average (and everyone's view of it: search, trip screens, receipts) moves on the next load. The rater still sees their own rating on their receipt. |

---

## 7. Server settings

| Setting | Default | Service |
|---|---|---|
| `SOS_REALERT_MINUTES` | `5,15,30` | trip-service - re-alert thresholds |
| `SOS_REALERT_MAX_AGE_HOURS` | `12` | trip-service |
| `SOS_OPS_PHONES`, `SOS_OPS_EMAILS` | - | notification-service - who gets SOS alerts and re-alerts |
| `OPS_STREAM_TOKEN_TTL_SECONDS` | `60` | ops-service |
| `OPS_STREAM_MAX_CONNECTION_MINUTES` | `30` | ops-service |
| `OPS_STREAM_HEARTBEAT_SECONDS` | `15` | ops-service |
| `OPS_STREAM_REPLAY_SIZE` | `500` | ops-service - events kept for Last-Event-ID replay |

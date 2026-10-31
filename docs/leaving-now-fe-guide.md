# Leaving Now & Change Trip Time: FE Guide

Trip-service: base path `/trip`, port `9103`. Ops-service: base path `/ops`.

Every response is wrapped in the standard envelope `{ responseId, timestamp, success, message, data }`. Errors come back with `success: false` and a readable `message` that you can show to the user as-is.

**Nothing existing has changed shape.** Every change in this guide is one of two kinds:
- a new endpoint;
- a new field at the end of an existing response, or a new optional field on an existing request.

Requests built for the current app keep working unchanged.

The ground rules throughout:
- A trip only exists because a car owner posted it. Riders never request a car.
- Other riders are only ever shown as **counts per area**, never names, photos or exact locations.

---

## 0. Answers to the "please confirm" list (A1–A4)

### A1. Leave-now posting
- **Yes.** `departureTime` = now + 60 s is accepted.
- **The leave-now window is 30 minutes.** Any route departing within 30 minutes is active straight away. `GET /trip/bookings/drivers/{userId}/active` returns it, so the app can open the passenger screen. The driver's "get ready" push is skipped for a route posted inside that window.
- **A departure in the past is accepted if it's up to 5 minutes ago** (a slow network). Older than that, you get `400 "Departure time has already passed"`.
- All the usual posting checks still apply: verified ID, a payout account, the owed limit, and no clash with another of their trips.

### A2. Rider search with "now"
- **Yes.** A leave-now route shows up as soon as it's posted. The time window is ±60 minutes around the rider's time.
- **Pickup radius:**
  - A route that starts and ends near the rider's own start and end matches within **2 km** at each end.
  - A rider joining part-way along the driver's road matches within **1 km** of that road.
  - A driver 0–300 m away in a car park always matches.
- **Watch out:** search only returns routes that **haven't started** and **still have seats**. Once the driver taps **Start trip**, the route disappears from search. Use the count in section 4 to ask "2 people near you are still looking. Start anyway?" before starting.

### A3. Notify-me while waiting
- **Yes.** A matching leave-now route posted later triggers the `NOTIFY_ME_MATCHED` push straight away.
- **Send `expiresInMinutes: 45`** (see section 2). Waiting then ends 45 minutes from now. Without it, a one-off stays active until its time + 60 minutes.
- When the rider taps **Stop waiting**, call `DELETE /trip/routes/notify-me/{id}`.

### A4. Weekly alert
- A repeating notify-me only matches routes within **±60 minutes of `repeatTime`** on its days.
- **Send `anyTimeOfDay: true`** (see section 2) for "any time on Sundays". Keep sending `repeatTime` as the usual time.

---

## 1. Change trip time ("Running late")

For **every** trip, not only leave-now ones. A driver moves the trip instead of cancelling it.

```
POST /trip/routes/{routePostId}/change-time
{ "minutes": 30 }                               // +15 / +30 / +60 buttons
// or
{ "departureTime": "2026-11-01T13:30:00Z" }     // a picked time
```

Send **exactly one** of the two. `minutes` is added to the trip's current departure (1–1440).

**Response `data`:**
```json
{
  "routePostId": "…",
  "previousDepartureTime": "2026-11-01T12:30:00Z",
  "departureTime": "2026-11-01T13:00:00Z",
  "originalDepartureTime": "2026-11-01T12:30:00Z",
  "timeChangeCount": 1,
  "changesLeft": 2,
  "ridersTold": 3
}
```

**Rules:**

| Situation | Allowed |
|---|---|
| Nobody has booked | Earlier or later, up to 3 days ahead, as often as needed. |
| Riders have booked | **Later only.** At most **2 hours** past the time each rider booked, and at most **3 changes**. |
| The trip has started, been cancelled or completed | Not allowed. |
| The new time is in the past, the same as now, or clashes with another of their trips | Not allowed. |

**Errors to show as-is** (all `400`):
- `Riders have booked this trip, so it can only move later. To leave earlier, cancel it and post again.`
- `This trip's time has already been changed 3 times. To change it again, cancel it and post again.`
- `Riders have booked this trip, so it can move at most 120 minutes later than they booked - up to 30 more minutes.`
- `Only a trip that hasn't started can change its time - this one is IN_PROGRESS`
- `The new departure time has already passed` / `That's already the departure time`
- `Send either the new departureTime or minutes to add, not both`
- Someone else's trip → `401`.

**Driver app:** put a **Running late / Change time** button on every upcoming trip, and show `changesLeft` when riders have booked.

**Rider app.** Every paid rider gets a push:
- `type = ROUTE_TIME_CHANGED`, with their `bookingId`.
- Example text: "Your ride Church X -> Ajah now leaves Sun 1 Nov at 2:00 PM (was Sun 1 Nov at 1:30 PM). Your seat is kept."
- When the trip has moved more than **15 minutes** past what they booked, the text adds that they can cancel free.

`GET /trip/bookings/{bookingId}` has new fields at the end:

| Field | Meaning |
|---|---|
| `originalDepartureTime` | The time before the first change. `null` if it never changed. |
| `timeChangeCount` | How many times it changed with riders booked. You could show "time changed 2×". |
| `freeCancellation` | `true` means cancelling is free, with a full refund. Show **Keep my seat** / **Cancel – full refund**. |

Cancel with the existing `POST /trip/bookings/{bookingId}/cancel`. When `freeCancellation` is true, the late-cancellation fee is not charged.

Search results and route details also carry `timeChangeCount` (and route details carries `originalDepartureTime`).

---

## 2. Small additions

### Car colour
`vehicleColour` (for example `"Silver"`, or `null`) is now on:
- search results (`POST /trip/routes/search`),
- route details,
- booking details,
- the shared-trip page.

Show it with the make, model and plate. **There is no car photo**, because we delete the exterior photo once ops approves the car.

### Notify-me options
Two new optional fields on `POST /trip/routes/notify-me`:

| Field | For | Meaning |
|---|---|---|
| `expiresInMinutes` (5–720) | one-off only | Stop waiting this many minutes from now. Use **45** for leave-now. |
| `anyTimeOfDay` (boolean) | repeating only | A trip at any time on those days counts. Still send `repeatTime`. |

Sending `expiresInMinutes` on a repeating request, or `anyTimeOfDay: true` on a one-off, gets a `400`. The response has a new `anyTimeOfDay` field.

```json
{ "originLat": 6.6, "originLng": 3.35, "destinationLat": 6.47, "destinationLng": 3.58,
  "originLabel": "Church X", "destinationLabel": "Ajah",
  "repeatDays": ["SUNDAY"], "repeatTime": "13:30", "repeatWeeks": 12, "anyTimeOfDay": true }
```

### Home
Riders and drivers each have one Home.

```
GET  /trip/saved-places/home            → the Home place, or data: null
PUT  /trip/saved-places/home            { "label": "Ajah", "lat": 6.47, "lng": 3.58, "formattedAddress": "optional" }
```

- Setting Home again replaces it.
- Home also appears in `GET /trip/saved-places` with `type: "HOME"`.
- `POST /trip/saved-places` with `type: "HOME"` replaces the existing Home too.
- Delete it with the existing `DELETE /trip/saved-places/{id}`.
- Home is now stored on the server, so it survives a phone change.

### Recent destinations
```
GET /trip/me/recent-destinations?limit=5      (1–20)
→ [ { "label": "Lekki", "lat": 6.53, "lng": 3.47, "lastUsedAt": "…" }, … ]
```

- Newest first.
- It covers places the caller rode to and trips they drove. Cancelled trips are left out.
- Places within 300 m of each other count once.

---

## 3. Riders waiting nearby (counts)

```
GET /trip/routes/demand/nearby?lat=6.6&lng=3.35
    [&radiusMeters=500]                         // default 500, max 2000
    [&routePostId=…]                            // the caller's own trip: riders it can carry
    [&destinationLat=6.47&destinationLng=3.58]  // riders on the way from here to there
→ [ { "areaLabel": "Ajah", "destinationLat": 6.465, "destinationLng": 3.585, "waitingCount": 3 }, … ]
```

**Who counts as waiting:** riders near this spot with either
- an active one-off notify-me for about now (±60 min), or
- a search from here in the last 15 minutes for about now.

**Who is never counted:**
- the caller,
- anyone blocked either way,
- riders who already have a seat.

**Grouping:** results are grouped by destination area, biggest first. Areas with **fewer than 2** riders are hidden. `destinationLat/Lng` is the middle of the area, never a rider's point. `areaLabel` is the name most of them gave, or `null`.

Use these filters for each screen:

| Screen | Call with |
|---|---|
| Rider waiting screen ("3 others near you are also going to Ajah") | `lat/lng` = here. Pick the group nearest their destination; the caller isn't counted. |
| Driver Home card ("4 people near you are going your way") | `lat/lng` = here, plus their Home as `destinationLat/Lng`. Sum `waitingCount`. |
| Driver confirm screen / before **Start trip** | `lat/lng` = here, plus `routePostId` = their trip. |

Errors:
- `routePostId` together with a destination → `400`.
- Only one of `destinationLat` / `destinationLng` → `400`.
- Someone else's `routePostId` → `401`.

---

## 4. Tell drivers when riders near them are going their way

### Send the driver's location
The driver app sends its location **when it opens and when it comes back to the front**:

```
PUT    /trip/drivers/me/location   { "lat": 6.6, "lng": 3.35 }
DELETE /trip/drivers/me/location                                 // on sign-out
```

The location is kept for 30 minutes, as one row per driver that is overwritten, never a history. Only these alerts use it.

### The push
- `type = DRIVER_NEARBY_DEMAND`, with no ids.
- Example text: "4 people near you are going to Ajah. Leaving now? Share your empty seats."
- On tap, open **I'm leaving now** with their Home, and call section 3 for the live count.

**A driver is told only when all of these hold:**
- 2 or more riders are waiting within 500 m;
- those riders are going the driver's way: towards their Home, a regular route, or a trip they drove in the last 60 days;
- the driver can post (verified, has a payout account, not over the owed limit, not suspended);
- the driver hasn't already posted a trip leaving now.

At most once per 30 minutes, and 4 times a day.

---

## 5. Regular trips: the "get ready" push

The existing driver push (`type = DRIVER_DEPARTURE_REMINDER`, with `routePostId`), about 30 minutes before departure, now adds who's waiting. For example:

> Your route Church X -> Ajah departs in 28 minutes - 2 riders booked. 4 people near the start are going your way.

- With nobody waiting, the text is exactly as before.
- **Tap opens that trip.** Don't post a new one; the regular trip is already posted.
- Add **On time / +15 / +30 / +60** action buttons that call `change-time` from section 1.
- The push is sent again for the new time after a change.

---

## 6. Showing it's working

**Rider:** `GET /trip/bookings/{bookingId}` has two new fields:

| Field | Meaning |
|---|---|
| `soloCabEstimate` | Roughly what a solo cab costs for their stretch, in naira. |
| `estimatedSaving` | That estimate less what they paid, never below 0. |

Both are `null` when it can't be worked out or the feature is switched off. Show "You saved about ₦3,800 compared with a solo cab" on the receipt.

**Driver:** use the existing earnings summary for a month, `netEarned`, for "Riders covered ₦X of your costs this month". No new endpoint is needed.

**Ops Console:** `GET /ops/metrics/pilot?days=7` has a new `leavingNow` block:

| Field | Meaning |
|---|---|
| `tripsPostedLeavingNow` | Trips posted inside the half hour before they left. |
| `seatsBookedOnThem` | Seats booked on those trips. |
| `ridersWaited` | One-off notify-me requests made for about the moment they were created. |
| `ridersFoundASeat` | Of those, the ones that got a seat. |
| `waitFoundSeatRate` | `ridersFoundASeat` / `ridersWaited`. |
| `unmetAreas` | Where waiting riders were going and no car came, most first. |
| `timeChanges` | Trip time changes in the window. |
| `lastMinuteTimeChanges` | Changes within an hour of departure with riders booked. |

`GET /ops/trips/time-changes/last-minute?days=30&minLastMinute=2` lists the drivers who most often move booked trips at the last minute.

---

## Checklist

**Rider app**
- [ ] Leave-now search sent from here and now, with Home and recent destinations.
- [ ] `vehicleColour` shown on result cards.
- [ ] Waiting screen: notify-me with `expiresInMinutes: 45`, the count from section 3, and **Stop waiting** → `DELETE`.
- [ ] Weekly alert sent with `anyTimeOfDay: true`.
- [ ] `ROUTE_TIME_CHANGED` push handled, with **Keep my seat** / **Cancel – full refund** when `freeCancellation`.
- [ ] Saving shown on the receipt.

**Driver app**
- [ ] `PUT /trip/drivers/me/location` on open and resume, `DELETE` on sign-out.
- [ ] Home card count (section 3, using Home).
- [ ] **I'm leaving now → Home**.
- [ ] `DRIVER_NEARBY_DEMAND` push opens leave-now.
- [ ] "Still looking" check before **Start trip**.
- [ ] **Running late / Change time** on every upcoming trip.
- [ ] `DRIVER_DEPARTURE_REMINDER` push opens that trip, with +15/+30/+60 actions.

**Ops Console**
- [ ] `leavingNow` block on the pilot page.
- [ ] Last-minute time changes list.

# Ops Console: Trips & Live Trips API

Base URL: `https://api-dev.coryd.ng/ops`. Every endpoint needs an **`OPS_ADMIN`** token (`Authorization: Bearer …`).
Responses use the standard envelope `{ responseId, timestamp, success, message, data }`; errors return `success: false`
with a `message` you can show as-is.

A **trip** is one route a driver posted: one driver, one vehicle, one departure, and every passenger booked on it.

---

## 1. Trips table: `GET /ops/trips`

| Param | Default | Values |
|---|---|---|
| `status` | none (All) | `ACTIVE` · `COMPLETED` · `CANCELLED`. **Leave it out for the All tab.** `ACTIVE` = scheduled, full or under way. |
| `search` | none | Matches either end of the route, or the start of the trip id |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

Newest departure first. An unknown `status` returns `400`: `Invalid status 'OPEN' - must be one of [ACTIVE, COMPLETED, CANCELLED]`.

`data` is the standard paginated shape used across CoRyd:

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

Mapping to the design:
- **Driver ID:** `driver.ref`.
- **Name and phone:** `driver.name` and `driver.phone`.
- **Vehicle:** `make model year` and `plateNumber`.
- **Route:** `originLabel → destinationLabel`.
- **Riders rating:** `driverRating`, the riders' average for this driver.
- **Total fare:** `totalFare`.
- **Status pill:** `group`. Use `status` for the finer state (`PUBLISHED`, `FULL`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`).
- **"Showing 1–20 of N":** `page`, `size`, `totalElements`.

`totalFare` and `seatsBooked` count only paid passengers who rode or are still booked. Unpaid holds, cancellations and no-shows aren't included, so a cancelled trip shows `0`.

**Sorting:** the list is always newest departure first. If the design keeps sortable column headers, sort the current page in the app.

**Missing details:** if driver, vehicle or phone details can't be loaded, those fields come back `null` and the row still shows. Render a dash.

### Tab counts: `GET /ops/trips/counts?search=`

```json
{ "all": 127, "active": 35, "completed": 82, "cancelled": 12 }
```

Pass the same `search` as the table so the badges match what's listed.

---

## 2. Trip details (the "View" action): `GET /ops/trips/{routePostId}`

```json
{
  "trip": { "…same shape as a table row…" },
  "originLat": 6.6, "originLng": 3.5, "destinationLat": 6.43, "destinationLng": 3.42,
  "routeNote": "via Third Mainland",
  "estimatedDurationMinutes": 40,
  "location": { "available": true, "lat": 6.51, "lng": 3.47, "recordedAt": "…" },
  "passengers": [
    {
      "bookingId": "…", "riderId": "…", "name": "Amaka Obi", "phone": "08011112222", "photoUrl": null,
      "seatCount": 1, "fareTotal": 1200.00, "status": "IN_PROGRESS",
      "pickupLabel": "Ikorodu garage", "pickupLat": 6.61, "pickupLng": 3.51,
      "confirmedAt": "…", "pickedUpAt": "…"
    }
  ]
}
```

- **Passengers:** every rider who **paid**, including those who later cancelled or didn't show, so ops sees what happened. Unpaid holds are never listed.
- **Location:** `available: true` only while the trip is under way and the driver's app is sending its position.

## 3. Live Trips monitor: `GET /ops/trips/live?search=`

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
      "elapsedMinutes": 18, "estimatedDurationMinutes": 40, "etaAt": "2026-10-03T07:56:00Z",
      "progress": 0.45,
      "location": { "available": true, "lat": 6.45, "lng": 3.46, "recordedAt": "…" }
    }
  ]
}
```

- **`phase`:** `ACTIVE` means the driver has started the trip (teal). `BOARDING` means it departs within 30 minutes but hasn't started yet (amber).
- **Header:** `total` trips · `active` active · `boarding` boarding.
- **Map pins:** use `location.lat`/`lng` when `location.available` is true. A pin only exists once the driver has started and their app is sending a position, so boarding trips usually have no pin.
- **Card:**
  - progress bar: `progress` (0–1, based on the route's expected drive time)
  - seats: `seatsBooked / seatCapacity`
  - time: `elapsedMinutes` min elapsed, then `etaAt` in Lagos time
- **Order:** soonest departure first.
- **Refreshing:** poll every 15–30 seconds. A driver position with no update for 2 minutes stops being shown (`available: false`).
- **Search:** filters by route or trip id.

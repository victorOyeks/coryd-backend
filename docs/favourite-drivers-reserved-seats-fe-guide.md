# Favourite Drivers + Reserved Seats — FE Implementation Guide

Profile-service — base path `/profile` · port `9102`
Trip-service — base path `/trip` · port `9103`

Every response below is wrapped in the standard envelope: `{ responseId, timestamp, success, message, data }`. Examples show just the `data` payload unless the envelope itself matters.

This covers two connected pieces:
1. **Favourite Drivers** — a rider can follow a driver they liked.
2. **Reserved Seats** — when a driver posts a new route, they can reserve seats for their favourites. Each reserved seat comes out of general availability immediately, and the rider gets 15 minutes to accept it before it's released back into inventory.

---

## Part 1 — Favourite Drivers (profile-service)

All four endpoints are self-scoped off the caller's token — no `riderId`/`driverId` in the path for "my own" calls.

### Rider side

```
POST   /profile/riders/me/favorite-drivers/{driverId}   → add (idempotent — favouriting twice is a no-op, not an error)
DELETE /profile/riders/me/favorite-drivers/{driverId}   → remove
GET    /profile/riders/me/favorite-drivers              → the rider's own list
```

`GET /profile/riders/me/favorite-drivers` response:

```json
{
  "data": [
    {
      "id": "fd_1a2b3c",
      "customId": "FAV-00042",
      "createdAt": "2026-09-01T10:15:00Z",
      "updatedAt": "2026-09-01T10:15:00Z",
      "riderId": "usr_rider_1",
      "driverId": "usr_driver_9"
    }
  ]
}
```

This is a raw list — it only carries `driverId`, not the driver's name/photo. If you're rendering a "Favourites" list with driver cards, batch-resolve the ids the same way you already do for search results (`POST /profile/drivers/batch` or whatever summary lookup you're using today).

### Driver side

```
GET /profile/drivers/me/favorited-by
```

This is the picker for step 2 below — every rider who has favourited the calling driver, already enriched with name/photo (no second lookup needed):

```json
{
  "data": [
    { "userId": "usr_rider_1", "fullName": "Adaeze Okafor", "photoUrl": "https://..." },
    { "userId": "usr_rider_2", "fullName": "Tunde Bakare", "photoUrl": null }
  ]
}
```

---

## Part 2 — Reserved Seats (trip-service)

### Step 1: driver posts a route with reservations

Same endpoint as today, `POST /trip/routes/drivers/{driverId}`, with one new optional field on the body:

```json
{
  "vehicleId": "veh_1",
  "originLabel": "Lekki Phase 1",
  "originLat": 6.4432,
  "originLng": 3.4726,
  "destinationLabel": "Ikeja",
  "destinationLat": 6.6018,
  "destinationLng": 3.3515,
  "departureTime": "2026-09-11T07:30:00Z",
  "seatCapacity": 4,
  "farePerSeat": 2500,
  "routeNote": "Leaving from the estate gate",
  "reservations": [
    { "riderId": "usr_rider_1", "seatCount": 1 },
    { "riderId": "usr_rider_2", "seatCount": 2 }
  ]
}
```

- `reservations` is optional — omit it (or send `[]`/`null`) for a normal, no-reservations post.
- Every `riderId` in the list **must** come from that driver's own `GET /profile/drivers/me/favorited-by` result — the backend re-checks this server-side (it never trusts the client's list), so build the picker from that endpoint rather than letting the driver type an id in free text.
- The sum of every `seatCount` in the list can't exceed `seatCapacity`.
- Each `riderId` can only appear once in the list.

Any of those three rules failing rejects the **whole** post with a 400 — no route gets created:

```json
{ "success": false, "message": "Reserved seats (5) can't exceed this route's seat capacity (4)" }
```

```json
{ "success": false, "message": "Rider usr_rider_3 hasn't favourited you - can't reserve a seat for them" }
```

```json
{ "success": false, "message": "Each favourite can only be reserved a seat once per route" }
```

On success, the returned `RoutePost` already reflects the reservations — `seatsAvailable` is pre-reduced by whatever you reserved, and `status` may already be `"FULL"` if the reservations filled every seat on a brand-new route. Nothing further to do on the driver's screen; the route posts exactly like any other post, just with fewer open seats showing from the first render.

### Step 2: each favourited rider gets notified

One push per reservation, fired the moment the route posts:

- Title: *"Your favourite driver reserved you a seat"*
- Body: *"{driverName} just posted a new trip and held {seatCount} seat(s) for you - accept within 15 minutes before it's released."*
- FCM data payload: `{ "type": "SEAT_RESERVED", "referenceId": "{bookingId}" }`

Tapping the notification should route the rider straight into `GET /trip/bookings/{bookingId}` using that `referenceId` — same deep-link pattern as every other push this app sends (`type` selects the screen, `referenceId` is the id you fetch).

There's no dedicated "my pending reservations" list endpoint — the push (and the existing notification bell feed, which already persists `bookingId` per notification) is the discovery path. If the rider dismissed the push and comes back later, the bell feed entry still has the `bookingId` to re-open the same screen.

### Step 3: the rider's "Accept or Decline" screen

`GET /trip/bookings/{bookingId}` — this is the **same endpoint** the normal Pay Now / Booking Confirmed screens already call, so if you're pointing an existing `BookingDetailsResponse` renderer at it, most fields need no new handling:

```json
{
  "data": {
    "bookingId": "bk_777",
    "routePostId": "rp_456",
    "status": "RESERVED",
    "originLabel": "Lekki Phase 1",
    "destinationLabel": "Ikeja",
    "departureTime": "2026-09-11T07:30:00Z",
    "seatCount": 1,
    "fareTotal": 2500,
    "farePerSeatSnapshot": 2500,
    "holdExpiresAt": "2026-09-10T14:47:00Z",
    "seatHoldMinutes": 15,
    "driverId": "usr_driver_9",
    "driverName": "Chidi Eze",
    "driverPhone": "+2348012345678",
    "driverPhotoUrl": "https://...",
    "driverVerified": true,
    "vehicleMake": "Toyota",
    "vehicleModel": "Corolla",
    "vehiclePlate": "LND-234-KJ",
    "vehicleHasAC": true
  }
}
```

Two things that differ from the normal Pay Now screen:

- `status` is `"RESERVED"`, not `"HELD"` — use this to switch the screen into "Accept / Decline" mode instead of "Pay Now" mode.
- `seatHoldMinutes` is **15** here (the reservation's real window), not the usual 4 — feed it and `holdExpiresAt` into the same countdown widget you already use for the payment hold, it just runs longer.

### Step 4a: rider accepts

```
POST /trip/bookings/{bookingId}/reservations/accept
```

Body — required, because a driver-created reservation never had a pickup point attached:

```json
{
  "pickupLabel": "Chevron Drive junction",
  "pickupLat": 6.4402,
  "pickupLng": 3.4701
}
```

Response is the updated `Booking`: `status` flips to `"HELD"`, `holdExpiresAt` resets to a fresh 4-minute window. **From this exact point on, treat it as a completely normal booking** — same `GET /bookings/{bookingId}` shape (now `seatHoldMinutes: 4`), same `POST /payments/initiate` flow, same `SeatHoldExpiryJob` timeout behaviour if the rider then doesn't pay in time. Nothing about the payment screen needs to know this booking started life as a reservation.

Error cases:
- Reservation already accepted/declined/expired → 400, `"This reservation is no longer available - current status is {status}"`.
- Rider already has another active/booked/ongoing trip elsewhere → 400, `"You already have a booked or ongoing trip - complete or cancel it before accepting this reservation"`.
- Caller isn't this reservation's rider → 401 Unauthorized.

### Step 4b: rider declines

```
POST /trip/bookings/{bookingId}/reservations/decline
```

No body. The seat is released back into the route's `seatsAvailable` immediately (not held for the full 15 minutes), `status` → `"CANCELLED_RIDER"`. Same 400/401 error shapes as accept.

### Step 4c: rider does nothing

If neither endpoint is called within the 15-minute window, the backend's own sweep releases the seat and cancels the booking (`CANCELLED_RIDER`) automatically — no client action needed. If your countdown widget hits zero, just re-`GET` the booking (or trust the countdown) and drop back to whatever screen you'd show for an expired hold today.

---

## What NOT to expect

- **No driver-side notification** when a rider accepts, declines, or lets a reservation expire. The driver only finds out the normal way — by checking their manifest/seats-available count. (Flagged as an easy future addition if wanted, not built now.)
- **No separate "pending reservations" list.** Discovery is entirely push-notification + bell-feed + `GET /bookings/{bookingId}`, as above.
- **Accepting doesn't skip payment.** It only converts `RESERVED` → `HELD` with a fresh hold; the rider still pays through the normal flow afterward.

---

## The whole thing, start to finish

1. Rider favourites a driver: `POST /profile/riders/me/favorite-drivers/{driverId}`.
2. Driver, on the "Post a Route" screen, calls `GET /profile/drivers/me/favorited-by` to populate a "reserve a seat for a favourite" picker.
3. Driver selects riders + seat counts, includes them as `reservations` on `POST /trip/routes/drivers/{driverId}`.
4. Route posts with `seatsAvailable` already reduced; each favourited rider gets a `SEAT_RESERVED` push carrying the `bookingId`.
5. Rider taps the push → `GET /trip/bookings/{bookingId}` → render Accept/Decline with a 15-minute countdown from `holdExpiresAt`/`seatHoldMinutes`.
6. Accept → `POST /bookings/{bookingId}/reservations/accept` with pickup details → booking is now a normal `HELD` booking, proceed to payment exactly as usual.
7. Decline → `POST /bookings/{bookingId}/reservations/decline` → done, seat is already back in inventory.
8. If the rider does neither, the backend expires it on its own — nothing further for the client to do.

If any part of this is unclear, say which step and I'll go deeper on just that one.

# Bookable "Rides nearby" pins: FE Guide

Trip-service: base path `/trip`, port `9103`. Responses use the standard envelope `{ responseId, timestamp, success, message, data }`.

This answers `NEARBY-RIDES-BACKEND-REQUEST.md` point by point.

---

## 1. `GET /trip/routes/nearby?lat=&lng=&radiusKm=` (radiusKm defaults to 5)

Each pin now identifies its route. The change is additive: `lat` and `lng` are still there, so older builds keep working.

```json
{
  "count": 1,
  "pins": [
    {
      "lat": 6.4541,
      "lng": 3.3947,
      "routePostId": "r9f8...",
      "originLabel": "Campos Memorial Mini Stadium, Igbosere Road, Lagos",
      "destinationLabel": "Obalende Bridge, Obalende Road, Lagos",
      "departureTime": "2026-10-03T15:00:00Z",
      "seatsAvailable": 3,
      "farePerSeat": 1200.00,
      "driverFirstName": "Tunde",
      "driverPhotoUrl": "https://...",
      "driverRating": 4.8,
      "vehicleMake": "Toyota",
      "vehicleModel": "Corolla"
    }
  ]
}
```

- **`lat`/`lng`:** the route's **origin**, the driver's starting meeting point. It's the same point `GET /trip/routes/{id}` returns as `originLat`/`originLng`.
- **The nice-to-have fields:** all five are included. Any of them can be `null` if the driver's profile or vehicle can't be loaded, so render without them.
- **No pickup ETA on pins.** That's a Google Maps call per pin. The details screen already returns `pickupEtaMinutes` for the route that was tapped.

## 2. Filtering

Pins include only routes the requesting rider can book:

- status `PUBLISHED` with `seatsAvailable > 0`
- departing **between now and 24 hours from now** (that's the answer to the upper-limit question)
- origin within `radiusKm` of the given point
- **not** the requesting user's own routes
- sorted by departure time, soonest first

`count` always equals `pins.length`.

## 3. Privacy: decided as **public meeting points**

Route origins are treated as public meeting points, so the server doesn't blur anything. Please add the prompt in the post-route flow asking drivers to choose a public spot, such as a landmark, bus stop or junction, not their home address.

## 4. Confirmations

**`GET /trip/routes/{routePostId}?riderLat=&riderLng=`** works for any route without a prior search.

It now says whether this rider can book the route. These two fields are additive, so the response is otherwise unchanged:

```json
{
  "...": "...all existing fields...",
  "bookable": false,
  "unavailableReason": "This ride is full"
}
```

| Situation | `bookable` | `unavailableReason` |
|---|---|---|
| Open, seats left, departs in the future | `true` | `null` |
| No seats left | `false` | `This ride is full` |
| Driver cancelled it | `false` | `This ride has been cancelled` |
| Departure time passed | `false` | `This ride has already departed` |
| Trip started or completed | `false` | `This ride has already left` |
| The rider is this route's driver | `false` | `This is your own route` |

It still returns `200` with full details in all these cases, so screens that open a route after it filled keep working. When `bookable` is `false`, show `unavailableReason` and disable "Book".

A pin can become unbookable between the map loading and the tap, for example if the last seat goes. The details screen covers that case.

**Reserve still validates everything.** Even when `bookable` is `true`, the reserve call can return a `400` with a message to show. The most likely one is a clash with the rider's other trips: `This overlaps a ride you've booked - … Trips can't overlap.` See `upcoming-vs-active-trips-fe-guide.md`.

---

## Checklist

- [ ] Pin tap → `GET /trip/routes/{pin.routePostId}?riderLat&riderLng` → reserve → pay (existing flow)
- [ ] Show the pin preview from the new fields; handle `null` driver/vehicle fields
- [ ] On the details screen, disable "Book" and show `unavailableReason` when `bookable` is `false`
- [ ] Post-route flow: prompt drivers to choose a public meeting point

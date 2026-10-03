# Upcoming vs Active Trips: FE Guide

Trip-service: base path `/trip`, port `9103`.

Every response is wrapped in the standard envelope `{ responseId, timestamp, success, message, data }`. Errors come back with `success: false` and a readable `message` that you can show to the user as-is.

---

## The idea

A trip is now one of two things:

| | Upcoming | Active |
|---|---|---|
| When | Departs more than **30 minutes** from now | Departs within 30 minutes, **or** is already under way |
| App behaviour | Business as usual: Home stays usable | Trip mode, as today |

- A rider's booking becomes active 30 minutes before departure, or as soon as the driver is en route, has arrived, or the trip is in progress.
- A driver's route becomes active 30 minutes before departure, or as soon as they tap Start.

**The backend decides which is which.** Don't compute the 30 minutes in the app. Use the endpoints below, so the window can change without an app release.

---

## 1. Rider app

### Home

Call both endpoints on Home load:

```
GET /trip/bookings/riders/{riderId}/active      ← unchanged path, new meaning
GET /trip/bookings/riders/me/upcoming           ← new
```

- **`/active` returns data:** show the trip screen (`OnTripHome`), as today. It now returns **only** a trip that is active (see the table above), and if several are active, the soonest. A ride booked for tomorrow no longer comes back here, so Home stays normal.
- **`/active` returns `null`:** show the normal Home. If `/upcoming` has items, add an **"Upcoming trips"** card at the top.

`/upcoming` response (soonest first; `[]` if none):

```json
[
  {
    "bookingId": "b1c2...",
    "routePostId": "r9f8...",
    "status": "CONFIRMED",
    "originLabel": "Campos Memorial Mini Stadium, Igbosere Road, Lagos",
    "destinationLabel": "Obalende Bridge, Obalende Road, Lagos",
    "departureTime": "2026-10-03T15:00:00Z",
    "seatCount": 1,
    "fareTotal": 1200.00,
    "pickupLabel": "Igbosere Road bus stop",
    "driverName": "Tunde Bakare",
    "vehiclePlate": "LND-123-XY"
  }
]
```

Tapping a card opens trip details via the existing `GET /trip/bookings/{bookingId}`, which includes the `pickupCode`.

When a trip moves into the active window, `/active` starts returning it. Re-fetch on app foreground and when a reminder push arrives (see section 3).

### Booking more than one ride

- A rider can now hold several bookings, as long as the trips **don't overlap**. Booking a ride to work and a ride home later the same day is fine. Booking a ride home while on the way to work is also fine.
- **Overlap rule:** each trip occupies its departure time, plus the expected drive time from Google Maps, plus a 30-minute buffer. A second trip can't start inside that window.
- The check also works across roles: a user can't book a ride during a route they're driving, and the reverse.

Booking an overlapping ride returns `400`:

| Situation | `message` |
|---|---|
| Overlaps another booked ride | `This overlaps a ride you've booked - Campos … to Obalende … on Fri 3 Oct, 4:00 PM. Trips can't overlap.` |
| Overlaps a route they're driving | `This overlaps a route you're driving - … on Fri 3 Oct, 4:00 PM. Trips can't overlap.` |
| Same ride already booked | `You've already booked a seat on this ride` |

The old message `You already have a booked or ongoing trip…` no longer exists.

Only one unpaid booking (Pay Now) can be open at a time. Starting a new booking releases the previous unpaid hold, as before.

---

## 2. Driver app

### Home

```
GET /trip/bookings/drivers/{driverId}/active     ← unchanged path, new meaning
GET /trip/bookings/drivers/me/upcoming           ← new
```

- **`/active`:** returns only a route that departs within 30 minutes or is in progress. Show the **"Live · Active Trip"** tile only when this returns a route.
- **"Post a Route":** keep it visible at all times. Drivers can schedule several routes.
- **`/upcoming`:** list these under **"Upcoming routes"**. Tapping one opens the existing passenger manifest, `GET /trip/bookings/routes/{routePostId}`.

`/upcoming` response (soonest first; `[]` if none):

```json
[
  {
    "routePostId": "r9f8...",
    "originLabel": "Campos Memorial Mini Stadium, Igbosere Road, Lagos",
    "destinationLabel": "Obalende Bridge, Obalende Road, Lagos",
    "departureTime": "2026-10-03T15:00:00Z",
    "status": "PUBLISHED",
    "seatCapacity": 4,
    "seatsAvailable": 2,
    "seatsBooked": 2,
    "farePerSeat": 1200.00
  }
]
```

`status` is `PUBLISHED` or `FULL`.

### After posting a route (`review-publish.tsx`)

The endpoint is unchanged: `POST /trip/routes/drivers/{driverId}`. After it succeeds:

1. Call `GET /trip/bookings/drivers/{driverId}/active`.
2. **If it returns the route you just posted** (same `routePostId`), this is a "leave now" route, so go to the passenger screen (`ready-to-depart`) as today.
3. **Otherwise**, go back to Home with a confirmation such as **"Route scheduled for Fri, 4:00 PM"**.

New `400` messages on posting:

| Situation | `message` |
|---|---|
| Overlaps another of their routes | `This overlaps a route you're driving - … on Fri 3 Oct, 4:00 PM. Trips can't overlap.` |
| Overlaps a ride they booked as a rider | `This overlaps a ride you've booked - … Trips can't overlap.` |
| Departure time already passed | `Departure time has already passed` |

The old message `You already have an active route…` no longer exists.

### Starting a route

Start (`POST /trip/routes/{routePostId}/start`) opens **30 minutes before departure**, the same moment the route becomes active. Tapping it earlier, for example from "Upcoming routes", returns `400`:

> `You can start this trip from Fri 3 Oct, 3:30 PM, 30 minutes before departure`

Show the message as-is. You can also grey out Start until the route appears in `/drivers/{id}/active`.

### A route that's never started

- At departure time, if the driver hasn't tapped Start, they get a **"Your trip was due to leave"** push (`ROUTE_START_OVERDUE`) asking them to start or cancel.
- **45 minutes after departure**, an unstarted route is cancelled automatically. Every rider is fully refunded and told "Your driver didn't start this trip. You've been fully refunded." The driver gets a `ROUTE_CANCELLED` push explaining why.

---

## 3. Reminder pushes and deep links

Notifications are about either **one booking** or **one route**, and each kind has its own field:

| | FCM data payload | In-app feed (`GET /notification/notifications/{userId}`) |
|---|---|---|
| About a booking | `type`, `referenceId` (= bookingId) | `eventType`, `bookingId` |
| About a route | `type`, `routePostId` | `eventType`, `routePostId` |

**`referenceId` and the feed's `bookingId` only ever hold a booking ID.** Route notifications leave them empty and use `routePostId`.

| `type` | Who | When | Id field | Open |
|---|---|---|---|---|
| `DRIVER_DEPARTURE_REMINDER` | Driver | 30 min before departure (when the route turns active) | `routePostId` | Passenger manifest / ready-to-depart |
| `ROUTE_START_OVERDUE` | Driver | At departure, if not started | `routePostId` | Passenger manifest, with Start / Cancel |
| `ROUTE_CANCELLED` | Driver | Route cancelled (by them or automatically) | `routePostId` | Upcoming routes / history |
| `ROUTE_DEPARTURE_REMINDER` | Each confirmed rider | 15 min before departure | `bookingId` | Trip details (shows the pickup code) |
| `BOOKING_CANCELLED` | Rider | Their booking was cancelled (by the driver, automatically, or by them) | `bookingId` | Trip details |

- The driver's 30-minute reminder replaces sending them to the passenger screen straight after posting.
- A "leave now" route (posted already inside the 30 minutes) doesn't get the driver reminder, because the driver is already on that screen.
- On receiving any of these, re-fetch `/active` and `/upcoming`, since the trip may have just turned active.

---

## Checklist

**Rider app**
- [ ] Home: render `OnTripHome` only when `/riders/{id}/active` returns a trip
- [ ] Home: "Upcoming trips" card from `/riders/me/upcoming`, tapping into trip details
- [ ] Show the overlap messages on booking failures; remove handling for the old "already have a trip" message
- [ ] Deep-link `ROUTE_DEPARTURE_REMINDER` (bookingId) to trip details

**Driver app**
- [ ] Home: "Live · Active Trip" tile only when `/drivers/{id}/active` returns a route
- [ ] Home: keep "Post a Route" always visible; add "Upcoming routes" from `/drivers/me/upcoming`
- [ ] After publish: call `/active`, open ready-to-depart only if it returns the new route, otherwise go Home with "Route scheduled for …"
- [ ] Show the overlap / "already passed" messages on post failures
- [ ] Deep-link `DRIVER_DEPARTURE_REMINDER` and `ROUTE_START_OVERDUE` to the manifest using `routePostId` (FCM data and feed)
- [ ] Show the server's message when Start is tapped more than 30 minutes before departure

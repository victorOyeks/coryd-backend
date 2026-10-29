# Track Live Trip — FE Implementation Guide

Base path `/trip` · port `9103` · WS endpoints `/trip/ws-native` (mobile, plain WS) · `/trip/ws` (browser, SockJS)

This walks through the Track Live Trip screen in the order things actually happen, in plain language, with the exact API call to make at each step. Read it top to bottom like a story, not like a reference table.

---

## The one idea you need before anything else

Almost everything on this screen works exactly like every other screen you've built: your app asks the server a question, the server answers, done. Ask → answer → close.

There is exactly **one** exception: the moving car icon on the map. For that one thing, instead of your app repeatedly asking "where's the driver now? where's the driver now? where's the driver now?", your app opens **one connection that just stays open**, and the server pushes new positions down that same open connection whenever it has one. Your app never has to ask twice.

That "stays open" connection is called a **WebSocket**. It's the only part of this screen that isn't a normal request/response call. Everything else — driver's name, trip status, fare, Share Trip, SOS — is a completely normal call, same as anything else you've already built.

Keep that one distinction in your head and the rest of this doc is just "here's what to call, in order."

---

## Step 1 — Rider opens the Home screen

Your app doesn't know yet if there's even a trip happening. So it asks:

```
GET /bookings/riders/{riderId}/active
Authorization: Bearer {accessToken}
```

Two things can come back:
- **Nothing** (empty) — no trip in progress, show the normal Home screen.
- **A booking** — show the trip card. The response tells you the `status` (e.g. `CONFIRMED`, `EN_ROUTE_TO_PICKUP`, `AT_PICKUP`, `IN_PROGRESS`), the driver's name/photo/car, the fare, and — importantly — the `bookingId`. You'll need that id for every call from here on.

```json
{
  "data": {
    "bookingId": "b_123",
    "status": "EN_ROUTE_TO_PICKUP",
    "driverName": "Emeka Okafor",
    "vehicleMake": "Audi",
    "vehiclePlate": "LND 234 AB",
    "originLabel": "Ikorodu Bus Terminal",
    "destinationLabel": "Victoria Island (Eko Hotel)",
    "departureTime": "2026-08-01T06:45:00Z",
    "fareTotal": 1800.00
  }
}
```

**What the `status` field means for what you show:**

| status | What the rider sees |
|---|---|
| `CONFIRMED` | "Pick up in X mins" (just a countdown, nothing live yet) |
| `EN_ROUTE_TO_PICKUP` | "Pick up in X mins" (now this can be a live number, driver is moving) |
| `AT_PICKUP` | "Your Driver has Arrived" + an "I've boarded" button |
| `IN_PROGRESS` | "You're on your way" |
| `COMPLETED` | trip's over, send them to the rating screen instead |

That one field is the entire logic for which version of the card to draw. Nothing else to infer.

---

## Step 2 — Rider taps into the full Track Live Trip screen

Now your app has a `bookingId` in hand, so instead of the "do I even have a trip" question from Step 1, you ask directly for that specific trip:

```
GET /bookings/{bookingId}
Authorization: Bearer {accessToken}
```

This gives you everything the card in Step 1 gave you, plus four coordinate fields: `originLat`, `originLng`, `destinationLat`, `destinationLng`. The backend no longer computes the "via Ketu bypass" route-preview text itself — you already have a Google Maps SDK on-screen for this map, so call Google Directions directly with those four coordinates and read `route.summary` off the response for that text, instead of waiting on a field from us. See **Maps & ETA — what moved to your side** at the bottom of this doc for the full picture.

(Full driver-side trip flow and the complete Maps/ETA writeup are in the separate `driver-trip-flow-fe-guide.md` doc, shared alongside this one.)

Then, separately, ask for the status timeline (the checklist with timestamps: Booking confirmed 06:30 AM, Driver en route 06:38 AM, etc.):

```
GET /bookings/{bookingId}/history
Authorization: Bearer {accessToken}
```

```json
{
  "data": [
    { "status": "CONFIRMED", "occurredAt": "2026-08-01T06:30:04Z" },
    { "status": "EN_ROUTE_TO_PICKUP", "occurredAt": "2026-08-01T06:38:41Z" },
    { "status": "AT_PICKUP", "occurredAt": "2026-08-01T06:44:02Z" }
  ]
}
```

Draw all five rows (Confirmed / En route / At pickup / In progress / Arrived) yourself, and fill in a timestamp only where this list has a matching entry. Anything past the last entry in the list just shows a dash.

That's two calls and the whole screen is drawn, except for the moving car.

---

## Step 3 — Get a starting position for the car (before the live connection is even open)

Ask once for the driver's last known position, so the map isn't blank while you're setting up the live connection:

```
GET /bookings/{bookingId}/location
Authorization: Bearer {accessToken}
```

You'll get one of two shapes back. Either a real position:

```json
{
  "data": {
    "available": true,
    "lat": 6.6018,
    "lng": 3.3515,
    "recordedAt": "2026-08-01T06:41:12Z",
    "message": null
  }
}
```

Notice there's no `etaMinutes` here anymore — the backend used to guess it with straight-line distance ÷ an assumed speed, which was a poor estimate in a lagoon city like Lagos. Now it's your job: call Google Directions/Distance Matrix yourself with this `lat`/`lng` as the origin and `originLat`/`originLng` (from Step 2's response) as the destination, and use its `duration` field. Real road-and-traffic data, not a guess. See **Maps & ETA — what moved to your side** at the bottom.

Or "not yet," with `available: false` and a `message` explaining why — and that message is written for you to just show directly on screen, word for word, not something to re-word yourself:

- `"Live tracking isn't available for this trip right now."` — status hasn't reached `EN_ROUTE_TO_PICKUP` yet.
- `"Live tracking opens 30 minutes before departure."` — right status, just too early. Show your countdown instead.
- `"Waiting for the driver's location to come through."` — everything's eligible, the driver just hasn't sent a position yet. This is completely normal right after the driver taps "on my way" — don't treat it as an error.

---

## Step 4 — Open the live connection (the WebSocket)

Now, and only now, open that "stays open" connection from the intro. Two things happen in order:

**4a. Connect, and prove who you are.**

There are actually two endpoints, same broker behind both — pick based on the client:

| Endpoint | For | Transport |
|---|---|---|
| `{TRIP_BASE_URL}/ws-native` | **Mobile (React Native)** — use this one | Plain WebSocket upgrade, no extra framing |
| `{TRIP_BASE_URL}/ws` | Browser clients only | SockJS (handshake + long-poll/XHR fallback for browsers that lack real WebSocket support) |

React Native already has a native `WebSocket` implementation, so the SockJS layer (and its `sockjs-client` dependency, which itself leans on browser globals like `document`) doesn't buy anything on mobile — just connect straight to `/ws-native` with a plain WebSocket.

```
Connect to: {TRIP_BASE_URL}/ws-native
Header on that connect message: Authorization: Bearer {accessToken}
```

This is the one place the token doesn't go in a normal HTTP header — it gets attached as a header on the special "connect" message itself, because phones/browsers can't reliably attach custom headers to the initial connection request the way they can on a normal API call. Your WebSocket library (see the code below) handles this for you — you just tell it what header to attach.

If the token is missing or expired, the connection just gets dropped immediately — no error message comes back, it just closes. If that happens, treat it as "token's bad," refresh it, and try connecting again.

**4b. Tell the server which trip you want updates for.**

Once connected, subscribe to one specific address:

```
/topic/bookings/{bookingId}/location
```

The server checks you're actually the rider (or driver) on that booking before letting you subscribe. If you're not, the connection gets rejected the same silent way as a bad token.

**In code**, using `@stomp/stompjs` alone — no `sockjs-client` needed for the mobile path:

```js
import { Client } from '@stomp/stompjs';

const client = new Client({
  webSocketFactory: () => new WebSocket(`${TRIP_BASE_URL_WSS}/ws-native`), // wss:// scheme, not https://
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`,
  },
  reconnectDelay: 4000,
});

client.onConnect = () => {
  client.subscribe(`/topic/bookings/${bookingId}/location`, (message) => {
    const location = JSON.parse(message.body); // same shape as Step 3's response
    updateCarPositionOnMap(location);
  });
};

client.activate();

// later, when the rider leaves the screen:
client.deactivate();
```

That's the entire WebSocket setup. One connect, one subscribe, one handler.

---

## Step 5 — What's happening on the driver's side, in parallel

While the rider's screen is open and connected, the **driver's** app is doing something completely ordinary — just a normal `POST`, sent every so often (every 15-30 seconds is reasonable) as long as the trip is active:

```
POST /bookings/{bookingId}/location
Authorization: Bearer {driverAccessToken}
{ "lat": 6.6021, "lng": 3.3519 }
```

The instant that lands on the server, the server pushes that exact same data down the rider's already-open connection from Step 4. The rider's app never asked for it — it just arrives, and your `client.subscribe(...)` handler from the code above fires with it.

That's the full loop: driver posts → server pushes → rider's map updates. Repeat for the whole trip.

One thing to know: if the driver's app stops posting (killed app, dead signal) for more than 2 minutes, the server forgets the last position. A rider re-opening the screen after that gap will see "Waiting for the driver's location to come through." again, not a stale old pin. Don't hold onto an old position in your UI forever without showing something like "last seen 3 min ago."

---

## Step 6 — Status changes are NOT pushed to you

This is the part that trips people up: the WebSocket only ever tells you about position. It does **not** tell you when the driver taps "I've arrived" or "on my way."

So when the driver's app calls:

```
POST /bookings/{bookingId}/en-route            (driver taps "I'm on my way")
POST /bookings/{bookingId}/arrived-at-pickup    (driver taps "I've arrived")
```

...the rider's screen doesn't hear about it automatically. Your app has to notice by asking again — either re-call `GET /bookings/{bookingId}` on a timer (every 5-10 seconds is fine), or re-check whenever the app comes back to the foreground. When that call comes back with a new `status`, that's when you swap from "Pick up in X mins" to "Your Driver has Arrived."

The rider's own actions are normal button taps, same as anything else in the app:

```
POST /bookings/{bookingId}/picked-up     ("I've boarded" button → status becomes IN_PROGRESS)
POST /bookings/{bookingId}/cancel        ("Cancel ride")
POST /bookings/{bookingId}/share         ("Share trip details" → returns a link, texted to the emergency contact automatically)
POST /bookings/{bookingId}/sos?triggeredBy={riderId}&latitude=..&longitude=..   ("Alert emergency contact now")
```

The share link (from a rider's booking, or `POST /routes/{routePostId}/share` for a driver's whole trip) opens as a web page in the contact's browser: driver, car and plate, from → to, and the driver's position on a map, refreshed every 20 seconds. Nothing to build in the app. Asked for as JSON (`Accept: application/json`), the same link returns the data, now with `tripStatus`, `originLat/Lng`, `destinationLat/Lng` and `routePolyline` too.

---

## Step 7 — Rider leaves the screen

Close the connection you opened in Step 4:

```js
client.deactivate();
```

That's it. Nothing to tell the server, no cleanup call — just stop listening.

---

## The whole thing, start to finish

1. Home loads → `GET /bookings/riders/{riderId}/active` → got a `bookingId` and a `status`
2. Rider taps in → `GET /bookings/{bookingId}` + `GET /bookings/{bookingId}/history` → draw the screen
3. `GET /bookings/{bookingId}/location` once → draw a starting pin (or the right "not yet" message)
4. Open the WebSocket, subscribe to `/topic/bookings/{bookingId}/location`
5. Driver's app keeps `POST`-ing positions in the background → they arrive on the rider's open connection automatically → map updates live
6. Poll `GET /bookings/{bookingId}` every several seconds to catch status changes the socket won't tell you about
7. Rider leaves → close the connection

If any one step in that list is still fuzzy, tell me which number and I'll go deeper on just that one.

---

## Maps & ETA — what moved to your side

Short version: the backend now only ever hands you raw coordinates. It doesn't compute distance, duration, route lines, or ETA text anymore — you already have a Google Maps SDK rendering the map on-screen, so you call Google directly for all of that, using the coordinates this API gives you. Full explanation, code shape, and the reasoning (Lagos/lagoon routing, batching, etc.) is in the separate `driver-trip-flow-fe-guide.md` doc — the "Maps & ETA" section there covers this screen too, not just the driver-side ones.

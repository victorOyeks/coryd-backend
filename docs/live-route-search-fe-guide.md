# Live Route Search — FE Implementation Guide

Base path `/trip` · port `9103` · WS endpoints `/trip/ws-native` (mobile, plain WS) · `/trip/ws` (browser, SockJS)

This covers the rider Search screen — instead of polling `POST /routes/search` on a timer while the rider sits on the results list, you call it once and then get pushed a fresh result list whenever anything relevant changes (a new matching route posted, seats taken/freed, a route going `FULL`/reopening, cancelled, or started). Same WebSocket broker and STOMP client as Track Live Trip/Trip Group Chat if you've built either of those already.

---

## The one idea you need before anything else

**There's no separate "start a live search" endpoint.** The search you already call is the live search — you just add one query param:

```
POST /routes/search?sessionId={sessionId}
```

`sessionId` is a **UUID you generate on the client**, once, when the Search screen opens. Keep using that same UUID for every search call made from that screen — including if the rider changes date/origin/destination and searches again. The backend uses it to remember "this rider, this criteria" so it knows what to push updates for.

**Order matters**: the session only exists once you've called `POST /routes/search?sessionId=...` at least once. Subscribing to the WebSocket topic before that call has ever succeeded will be rejected — there's nothing to authorize yet.

---

## Step 1 — Generate a session ID when the screen opens

```js
import { randomUUID } from 'expo-crypto'; // or any UUID v4 source

const searchSessionId = randomUUID();
```

One per visit to the Search screen. Don't reuse it across app sessions or across a totally different search (e.g. the rider backs out and searches a different route later) — a fresh screen visit, a fresh UUID.

---

## Step 2 — Search, passing the session ID

```
POST /routes/search?sessionId={searchSessionId}
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "originLat": 6.6206269,
  "originLng": 3.503486,
  "destinationLat": 6.4308465,
  "destinationLng": 3.4306265,
  "desiredDepartureTime": "2026-09-21T05:00:00.000Z"
}
```

Response is exactly what you already parse today — nothing about the shape changed:

```json
{
  "data": [
    {
      "routePostId": "675d9767-c383-4f7c-b89a-948c6816adbb",
      "originLabel": "Ikorodu garage market, Ikorodu, Nigeria",
      "destinationLabel": "Eko Hotel Roundabout, Lagos, Nigeria",
      "departureTime": "2026-09-21T05:00:00Z",
      "farePerSeat": 2500.0,
      "seatsAvailable": 4,
      "driverId": "6085d9a7-bba1-4040-9031-d4feeb7eef60",
      "driverName": "Kunle Adeyemi",
      "driverVerified": true,
      "vehicleMake": "Toyota",
      "vehicleModel": "Corolla",
      "vehiclePlate": "SEED-001",
      "vehicleHasAC": true,
      "ratingAvg": 5.0,
      "ratingCount": 1,
      "pickupEtaMinutes": 6,
      "routeNote": null
    }
  ]
}
```

Render this as you already do. This response is also what "resets the baseline" for push updates — see Step 4.

If you omit `sessionId` entirely, this endpoint behaves exactly as before (a one-shot search, no live session created) — so nothing breaks if some caller still doesn't want live updates (e.g. the `nearby-options` fallback screen, which stays plain REST and isn't part of this feature).

---

## Step 3 — Subscribe for live updates

Same connection you'd already have open for Track Live Trip/Chat if the rider is mid-trip elsewhere in the app — on the Search screen specifically you're usually opening a fresh one:

```js
import { Client } from '@stomp/stompjs';

const client = new Client({
  webSocketFactory: () => new WebSocket(`${TRIP_BASE_URL_WSS}/ws-native`), // wss://, not https://
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`,
  },
  reconnectDelay: 4000,
});

client.onConnect = () => {
  client.subscribe(`/topic/search-sessions/${searchSessionId}`, (message) => {
    const results = JSON.parse(message.body); // same array shape as Step 2's response
    replaceSearchResults(results);
  });
};

client.activate();

// when the rider leaves the Search screen:
client.deactivate();
```

**Replace your results list wholesale with what arrives here** — this is always the full current result set for that search, not a diff/patch. Don't try to merge it with what you're already showing.

The server checks the subscribing user actually owns this `sessionId` (i.e. they're the same rider who called Step 2 with it). A mismatched or unknown session just closes the connection silently, same as every other topic in this app — no error frame.

---

## Step 4 — Re-searching (date/location changed)

If the rider adjusts their search on the same screen, just call Step 2 again with the **same** `searchSessionId`:

```
POST /routes/search?sessionId={searchSessionId}
```

This does two things at once: gives you the new result set immediately (as always), and re-points the live session at the new criteria — so from here on, pushes reflect the new search, not the old one. You don't need to re-subscribe; the WebSocket topic address (`/topic/search-sessions/{searchSessionId}`) doesn't change.

---

## What you will and won't get pushed

You'll get a push when:
- A new route is posted that matches your current criteria
- `seatsAvailable` changes on a route already in (or now newly in) your results — someone else books, cancels, or a hold expires
- A route in your results goes `FULL`, reopens, gets cancelled, or the driver starts the trip (it drops out of results)

You won't get a push for a change that doesn't actually affect your specific search — e.g. someone booking a seat on a route in a totally different corridor. And you won't get a duplicate push of results that haven't actually changed since your last search call or last push.

There's no guaranteed push cadence and no heartbeat payload — silence just means nothing relevant has changed. If the rider is staring at a static list for a while, that's expected, not a dead connection.

---

## When to stop

There's no explicit "end session" call. Just `client.deactivate()` (or drop the subscription) when the rider leaves the Search screen — same as you'd do for any other screen-scoped subscription. Left-open sessions clean themselves up server-side after 30 minutes of inactivity regardless, so a missed `deactivate()` on a crash/kill isn't a leak you need to worry about.

---

## Errors you can get back

| Status | When | What it means |
|---|---|---|
| `400` | Missing/invalid search fields | Same validation as before — unrelated to `sessionId` |
| *(WS closes silently)* | Subscribed to a `sessionId` you never searched with, or that's expired (30+ min idle), or belongs to another user | Search again (Step 2) with a fresh or the same `sessionId` before subscribing again |

---

## The whole thing, start to finish

1. Generate a UUID (`searchSessionId`) when the Search screen opens
2. `POST /routes/search?sessionId={searchSessionId}` → render the results, same as today
3. Subscribe to `/topic/search-sessions/{searchSessionId}` on your STOMP client
4. On push, replace your results list with the full array that arrives — don't merge/diff it yourself
5. Rider changes date/location? Call `POST /routes/search?sessionId={searchSessionId}` again with the **same** id — new results now, new criteria for future pushes
6. Leaving the screen → `client.deactivate()` (or just drop the subscription); no explicit teardown call needed

If any step is still fuzzy, say which number and I'll go deeper on just that one.

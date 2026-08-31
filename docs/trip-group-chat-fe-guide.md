# Trip Group Chat — FE Implementation Guide

Base path `/trip` · port `9103` · WS endpoints `/trip/ws-native` (mobile, plain WS) · `/trip/ws` (browser, SockJS)

This covers the Trip Group Chat screen — the single conversation shared by a route's driver and every rider currently booked on it. If you've already built Track Live Trip, this will feel familiar: same WebSocket broker, same STOMP client, same "connect once, subscribe to a topic" shape. Read it top to bottom.

---

## The one idea you need before anything else

**There is no "join chat" or "add member" API.** Whether you're allowed into a route's group chat is worked out fresh, every single call, from data that already exists:

- You're in if you're the **driver** of that route.
- You're in if you're a **rider with a booking on that route**, and that booking is currently `CONFIRMED`, `EN_ROUTE_TO_PICKUP`, `AT_PICKUP`, or `IN_PROGRESS`.
- You're out the moment neither of those is true — a booking that gets cancelled, or completes, drops you out automatically. Nobody has to remove you.

This is the exact same rule Track Live Trip already uses to decide who can see the driver's position, just extended to cover chat too. One consequence worth knowing up front: **a rider only enters the chat once their booking is `CONFIRMED`** — not the moment they pay, if there's a `HELD` state in between.

The other thing to know up front: **the whole conversation is deleted, permanently, the instant the trip ends** — whether it ends by completing normally or by the driver cancelling the route. Not archived, not hidden — the rows are gone from the database, and any voice notes are wiped from storage too. There's no "delete chat" button because there's nothing left for a button to do; it happens automatically server-side. See **When the chat ends**, below, for exactly what that looks like from your side.

---

## Step 1 — Get the `routePostId`

Chat is scoped to the **route**, not to an individual booking (that's the whole point — everyone on the same route shares one thread). So the first thing you need is the `routePostId`, and where you get it depends on which side you're building:

**Rider side** — it's now on the same response you're already calling for Track Live Trip:

```
GET /bookings/riders/{riderId}/active
GET /bookings/{bookingId}
```

```json
{
  "data": {
    "bookingId": "b_123",
    "routePostId": "rp_456",
    "status": "EN_ROUTE_TO_PICKUP",
    "...": "..."
  }
}
```

If you built Track Live Trip before this field existed, this is the one change needed on your end — grab `routePostId` off a response you're already parsing.

**Driver side** — already there, no change needed:

```
GET /bookings/drivers/{driverId}/active
```

```json
{
  "data": {
    "routePostId": "rp_456",
    "status": "IN_PROGRESS",
    "bookings": [ { "bookingId": "b_123", "riderId": "u_789", "riderName": "Amaka Obi" } ],
    "...": "..."
  }
}
```

Hang onto `routePostId` for the rest of this doc — every chat call needs it.

---

## Step 2 — Load the conversation so far

```
GET /routes/{routePostId}/chat/messages
Authorization: Bearer {accessToken}
```

```json
{
  "data": [
    {
      "id": "cm_1",
      "createdAt": "2026-08-31T06:41:12Z",
      "routePostId": "rp_456",
      "senderId": "u_789",
      "type": "TEXT",
      "content": "On my way to the pickup point now",
      "durationSeconds": null,
      "mediaPublicId": null
    },
    {
      "id": "cm_2",
      "createdAt": "2026-08-31T06:42:03Z",
      "routePostId": "rp_456",
      "senderId": "u_321",
      "type": "VOICE",
      "content": "https://res.cloudinary.com/coryd/video/upload/v123/coryd/uploads/a1b2c3.m4a",
      "durationSeconds": 8,
      "mediaPublicId": "coryd/uploads/a1b2c3"
    }
  ]
}
```

Comes back oldest-first, ready to render top-to-bottom. A brand-new route with no messages yet just returns `[]` — not an error.

**`senderId` is a bare user id — no name or photo comes attached.** You already have that data on-screen from getting into the chat in the first place (the driver card, or `riderName`/`riderPhotoUrl` in the Passenger Manifest's booking list) — build a local `senderId → {name, photo}` map from that instead of expecting the message payload to carry it. This keeps the message table small and avoids a second lookup on every single message.

---

## Step 3 — Open (or reuse) the live connection

If this screen already has a live-location WebSocket open (rider on Track Live Trip, driver watching the manifest), **don't open a second connection** — just add one more `subscribe()` call to the client you already have:

```js
client.subscribe(`/topic/routes/${routePostId}/chat`, (message) => {
  const chatMessage = JSON.parse(message.body); // same shape as Step 2's array items
  appendToChat(chatMessage);
});
```

Building the chat screen on its own, with no existing connection to piggyback on? Same setup as Track Live Trip, just a different subscribe address:

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
  client.subscribe(`/topic/routes/${routePostId}/chat`, (message) => {
    const chatMessage = JSON.parse(message.body);
    appendToChat(chatMessage);
  });
};

client.activate();

// later, when the screen closes:
client.deactivate();
```

Same rules as Track Live Trip's connection apply here: the server checks you're actually a party to this route before letting the subscribe through, and if you're not (or your token's bad), the connection just closes silently rather than sending back an error frame.

---

## Step 4 — Send a text message

```
POST /routes/{routePostId}/chat/messages
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "type": "TEXT",
  "content": "On my way to the pickup point now"
}
```

Response is the saved message (same shape as Step 2). You don't need to append it to your own UI from this response *and* from the WebSocket push — it arrives on your own open subscription same as everyone else's, so just wait for that and append once.

---

## Step 5 — Send a voice note

This is a two-call flow: upload the audio file first, then send a chat message that points at it.

**5a. Upload the recording** to profile-service (port `9102`, base path `/profile`):

```
POST /profile/media/upload
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

file: <the recorded audio file>
```

```json
{
  "data": {
    "url": "https://res.cloudinary.com/coryd/video/upload/v123/coryd/uploads/a1b2c3.m4a",
    "publicId": "coryd/uploads/a1b2c3"
  }
}
```

Accepted formats: m4a, aac, mp4, mpeg/mp3, wav, webm, 3gp — whatever your recorder library outputs on iOS/Android should already be in this list. Max 10 MB per file.

**5b. Send the chat message**, filling in all three voice-specific fields from that response:

```
POST /routes/{routePostId}/chat/messages
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "type": "VOICE",
  "content": "https://res.cloudinary.com/coryd/video/upload/v123/coryd/uploads/a1b2c3.m4a",
  "durationSeconds": 8,
  "mediaPublicId": "coryd/uploads/a1b2c3"
}
```

**Don't skip `mediaPublicId`.** `content` is what you actually play back, but `mediaPublicId` is what lets the backend delete the audio file itself from storage once the trip ends — leave it off and that file just sits there forever with nothing pointing at it. It's not shown anywhere in the UI; it's purely so cleanup can find the file later.

---

## When the chat ends

The moment a route reaches `COMPLETED` or the driver cancels it, three things happen automatically, with no call from you:

1. Every message for that route is deleted from the database — not archived, gone.
2. Any voice notes among them are deleted from storage too.
3. Both `GET` and `POST` on `/routes/{routePostId}/chat/messages` start rejecting everyone — driver included — with:

```json
{ "success": false, "message": "This trip's group chat has ended" }
```
at `401 Unauthorized`.

**Don't wait for that 401 to close the screen.** You're already polling the trip's `status` for exactly this reason (Step 6 of the Track Live Trip guide — the socket doesn't push status changes, only position/chat). The instant that poll shows `COMPLETED` or `CANCELLED`, close the chat screen (and `client.deactivate()` if this was its own connection) yourself. Treat the 401 as a backstop for a message somehow in flight right as the trip ended, not as your primary signal.

---

## Errors you can get back

| Status | When | What it means |
|---|---|---|
| `401` | Not a current party to the route, or the trip already ended | Not authorized — see above for the "trip ended" case specifically |
| `404` | `routePostId` doesn't exist | Shouldn't happen if it came from Step 1's response — treat as a bug if you see it |
| `400` | `type`/`content` missing on send | Validation — `type` and `content` are always required; `durationSeconds`/`mediaPublicId` only make sense for `VOICE` |

The WebSocket side fails differently: a bad token or a non-party subscribe just gets the connection closed with no error frame, same as Track Live Trip.

---

## The whole thing, start to finish

1. Get `routePostId` — from `GET /bookings/{bookingId}` (rider) or `GET /bookings/drivers/{driverId}/active` (driver)
2. `GET /routes/{routePostId}/chat/messages` → draw the history you already have
3. Subscribe to `/topic/routes/{routePostId}/chat` on your STOMP client (reuse the one from Track Live Trip if it's already open)
4. Text message → `POST .../chat/messages` with `{ type: "TEXT", content }`
5. Voice note → `POST /profile/media/upload` first, then `POST .../chat/messages` with `{ type: "VOICE", content: url, durationSeconds, mediaPublicId }`
6. New messages from anyone else arrive on your subscription automatically — append, don't re-fetch
7. Resolve `senderId` → name/photo yourself from data you already have on-screen (driver card / passenger manifest)
8. Keep polling trip `status` like you already do for Track Live Trip — the moment it's `COMPLETED` or `CANCELLED`, close the chat screen; the conversation is being deleted server-side at that same moment

If any step is still fuzzy, say which number and I'll go deeper on just that one.

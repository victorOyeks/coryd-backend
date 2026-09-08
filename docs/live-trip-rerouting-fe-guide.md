# Live Trip Rerouting — FE Implementation Guide

Base path `/trip` · port `9103` · WS endpoints `/trip/ws-native` (mobile, plain WS) · `/trip/ws` (browser, SockJS)

This covers one thing: **the route line on the Live Trip map can now update itself mid-trip.** There's no new REST endpoint, no new screen, and nothing to poll — it's a single additional WebSocket subscription on the connection you've already got open for Track Live Trip.

---

## The problem this fixes

Up to now, the yellow route line on Live Trip was drawn once — computed the moment the screen loaded (`GET /route-posts/{routePostId}/route-preview`, or the `overviewPolyline` field on Ride Details/Booking Details) — and never touched again. If the driver took a different road than planned, the line just sat there, disconnected from where the car marker actually was.

The backend now watches every location ping the driver posts and, if they've meaningfully and persistently left the plotted line, quietly recomputes the route from their current position and pushes the new line down. You don't have to detect anything yourself — just listen for it.

---

## The one thing to add: a second subscription

If you've already built Track Live Trip, you have a STOMP client open and subscribed to `/topic/routes/{routePostId}/location`. Add one more `subscribe()` call to that same client — don't open a second connection:

```js
client.subscribe(`/topic/routes/${routePostId}/route-update`, (message) => {
  const update = JSON.parse(message.body);
  redrawRouteLine(update.overviewPolyline); // same Google encoded-polyline format you already decode today
});
```

Same authorization rule as every other route-scoped topic (location, chat): the server checks you're actually the route's driver, or a rider with a non-terminal booking on it, before letting the subscribe through. If you're not, or your token's bad, the connection just closes silently — no error frame, same as the others.

Full connect/subscribe boilerplate (token in the CONNECT frame, not a query param) is identical to Track Live Trip's — see that guide's Step 4 if you're wiring this up standalone rather than piggybacking on an existing connection.

---

## What arrives on that topic

```json
{
  "data": {
    "routePostId": "rp_456",
    "overviewPolyline": "a~l~Fjk~uOwHJy@P",
    "distanceKm": 14.2,
    "durationMinutes": 27,
    "updatedAt": "2026-09-08T14:32:07Z"
  }
}
```

- `overviewPolyline` — Google's encoded polyline format, the exact same shape as the `overviewPolyline` field you already get from Ride Details/Booking Details/the route-preview endpoint. Feed it into whatever decode/render path you already have for those — nothing new to build there.
- `distanceKm` / `durationMinutes` — the recomputed trip's new distance/duration from the driver's current position. Update your ETA/distance display alongside the line if you're showing one.
- No `data` field carries anything about *why* it fired — there's no "driver went off route" flag or reason code. Just treat every message on this topic as "here's the current line, redraw it" and move on.

---

## What NOT to expect

- **This won't fire often.** A single wrong turn or GPS blip won't trigger it — the server requires the driver to be measurably off the plotted line (over ~250m) for two consecutive location pings before it counts as a real deviation, not noise. Once it does fire, there's a cooldown before it can fire again for the same trip, so continued drift doesn't spam you with updates every few seconds.
- **No "recalculating..." signal.** The server doesn't tell you a reroute is in progress, only the result once it's done. If you want a brief "updating route..." moment in the UI, that has to be a client-side guess (e.g., a short timeout after you notice the car marker drifting) — there's nothing to key it off from the server side.
- **Don't treat silence as an error.** Most trips will never receive a message on this topic at all — that's the expected, common case (the driver stayed on the planned route), not a sign anything's broken.

---

## The whole thing, start to finish

1. On the same STOMP connection you use for Track Live Trip, add `client.subscribe('/topic/routes/{routePostId}/route-update', ...)`.
2. When a message arrives, decode `overviewPolyline` the same way you already do for the initial route line, and swap it in on the map.
3. Optionally update whatever distance/ETA text you're showing from `distanceKm`/`durationMinutes`.
4. That's it — no polling, no new REST call, nothing to unsubscribe beyond the same `client.deactivate()` you already call when the rider/driver leaves the screen.

If any part of this is unclear, say which step and I'll go deeper on just that one.

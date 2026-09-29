# Pickup Code, Late Cancellation Fee & Booking Changes: FE Guide

Trip-service: base path `/trip`, port `9103`. Payment-service: base path `/payment`, port `9104`.

Every response is wrapped in the standard envelope `{ responseId, timestamp, success, message, data }`. Errors come back with `success: false` and a readable `message` that you can show to the user as-is.

- `400` means the request isn't allowed right now (wrong code, wrong state, and so on).
- `401` means the caller isn't a party to this booking.

---

## 1. Pickup code

A driver can no longer mark a passenger as picked up just by tapping. They have to enter the rider's **4-digit pickup code**, which proves the two actually met. Each code belongs to one booking only. A code from another rider or another route will never work.

### Rider app

**Where the code comes from.** It's created the moment payment confirms the booking. Before payment there is no code.

**Where to show it.** It's the new `pickupCode` field on the booking details response. It appears on all three of these endpoints:

- `GET /trip/bookings/{bookingId}`
- `GET /trip/bookings/riders/{riderId}/active` (Home "current trip" card)
- `GET /trip/bookings/riders/me/reserved-seat`

```json
{
  "bookingId": "b1c2...",
  "status": "CONFIRMED",
  "...": "...all existing fields unchanged...",
  "pickupCode": "4821"
}
```

- `pickupCode` has a value only while the booking is **`CONFIRMED`, `EN_ROUTE_TO_PICKUP` or `AT_PICKUP`**. In every other status it's `null`, so hide the code UI then.
- Show it prominently on the Booking Confirmed and Track Live Trip screens, for example "Your pickup code: **4821**. Give it to your driver when you board."
- It's also in the "Booking confirmed" push notification, so a rider without data can still find it.

**"I've boarded" button (unchanged).** The rider can still confirm their own boarding, and no code is needed:

```
POST /trip/bookings/{bookingId}/picked-up
(no body)
```

This is also the fallback when the driver gets locked out (see below). If the driver says "the app won't accept your code", the rider taps "I've boarded".

### Driver app

**Mark Picked Up now requires the code.** On the Passenger Manifest, tapping "Mark Picked Up" should open a 4-digit code entry, then send:

```
POST /trip/bookings/{bookingId}/picked-up
Content-Type: application/json

{ "pickupCode": "4821" }
```

- The code is always 4 digits and may start with 0, e.g. `"0371"`. Send it as a **string**, never a number.
- The driver never receives the code from any endpoint. They can only get it from the rider.

**Responses:**

| Situation | Status | `message` |
|---|---|---|
| Correct code | `200` | `Rider marked as picked up` |
| No code / not 4 digits | `400` | `Enter the rider's 4-digit pickup code` (or `Pickup code must be 4 digits`) |
| Wrong code | `400` | `That pickup code doesn't match this rider's booking` |
| 5 attempts used up | `400` | `Too many wrong pickup codes - ask the rider to tap "I've boarded" in their app` |
| Booking already cancelled / completed / no-show | `400` | `This booking can't be marked picked up - current status is ...` |
| Trip already completed or cancelled | `400` | `This trip is already ... - it can no longer be changed` |
| Already picked up (repeat tap) | `200` | no-op, safe to retry |

**Attempt limit:**

- The driver gets **5 attempts per passenger**, and a correct code resets the count.
- Keep the code entry open after a wrong code so they can retry, and show the server's message.
- On the "Too many" message, stop offering code entry for that passenger and show: "Ask the rider to tap **I've boarded** in their app." The manifest then updates to picked up once the rider confirms.

**Undo** (`POST /trip/bookings/{bookingId}/undo-manifest-mark`) works as before. Marking the passenger picked up again after an undo needs the code again.

**Suggested driver flow:**

```
Manifest row → "Mark Picked Up"
  → Code sheet (4 boxes, numeric keypad)
     → submit → 200 → row shows Picked Up
               → 400 wrong code → shake + message, stay open
               → 400 "Too many" → close sheet, show "Ask rider to tap I've boarded"
```

---

## 2. Late cancellation fee (25%)

The rule applies when a **rider cancels a paid booking**.

| When they cancel | Refund to wallet |
|---|---|
| 2+ hours before departure | 100% (free) |
| Less than 2 hours before departure | **75%**. The 25% fee goes to the driver. |

- The endpoint is unchanged: `POST /trip/bookings/{bookingId}/cancel`.
- Driver cancellations and automatic cancellations are always a full refund.
- Cancelling an **unpaid** booking (still on Pay Now) is always free, with nothing to refund.
- The "Refund issued" push already shows the actual amount refunded, e.g. ₦900 on a ₦1,200 fare.

**Please add a warning on the cancel confirmation dialog** whenever `departureTime - now < 2 hours`:

> "You're cancelling less than 2 hours before departure. You'll be refunded ₦900 (75%) — ₦300 goes to the driver."

Compute it as `fareTotal × 0.25`, rounded to 2 decimal places. If you'd prefer the backend to return the exact fee rather than computing it in the app, tell us and we'll add a preview endpoint.

---

## 3. Other booking changes you'll notice

These shipped in the earlier booking fixes.

**Paying for a booking.** `POST /payment/payments/initiate` only needs:

```json
{ "bookingId": "...", "paymentMethod": "WALLET" | "PAYSTACK", "callbackUrl": "...", "riderEmail": "..." }
```

- `riderId`, `driverId` and `amount` are now **ignored**. The server reads them from the booking. Sending them is harmless, so you can drop them whenever convenient.
- Tapping Pay with Paystack again while a checkout is still open returns the **same** `authorizationUrl`. Don't treat that as an error; just reopen it.
- Paying for a booking whose 4-minute hold has expired returns `400`: `This booking is no longer awaiting payment - its seat hold has ended. Please book again.`

**Booking a seat** (`POST /trip/bookings/routes/{routePostId}`):

- `riderId` in the body is now **ignored**; the server uses the logged-in user.
- A retry (same route, same seat count, hold still active) returns the **same booking**, so it's safe to retry on network errors.
- A rider has only one booking at a time. Starting a booking on a different route automatically releases their previous unpaid hold.
- New `400` messages to handle:
  - `You can't book a seat on your own route`
  - `This ride is full`
  - `This ride is no longer taking bookings`
  - `This ride has already departed`
  - `You already have a booked or ongoing trip - complete or cancel it before booking another`

**Receipt and driver Trip History numbers.**

- `seatsBooked` and `totalEarned` now count only passengers actually carried. Expired holds, failed payments and cancellations were being counted before, which is why one trip showed 8 seats.
- The receipt `passengers` list now contains only riders who rode (`COMPLETED`) or didn't show (`NO_SHOW`).
- **Please render each passenger's badge from its `status`.** The receipt currently shows "Picked Up" for everyone, including riders who cancelled. Show "Completed" for `COMPLETED` and "No-show" for `NO_SHOW`.

**Rating** (`POST /trip/bookings/{bookingId}/rate`). Only `stars` (1–5) and optional `comment` are used now. The server works out who is rating whom from the booking. Other `400` responses:

- `A trip can only be rated once it's completed` if the trip isn't finished.
- `You've already rated this trip` for a second rating from the same side.

**Access checks.** Booking details, status history, share, SOS, and every `/riders/{id}/...` and `/drivers/{id}/...` endpoint now return `401` for anyone except that booking's rider or driver. The apps shouldn't hit this unless they're passing someone else's ID.

---

## Checklist

- [ ] Rider app: show `pickupCode` on Booking Confirmed and Track Live Trip while it's non-null
- [ ] Driver app: code entry before "Mark Picked Up", sending `{ "pickupCode": "...." }` as a string
- [ ] Driver app: handle the wrong-code and "Too many" messages; point the driver to the rider's "I've boarded"
- [ ] Rider app: late-cancellation warning with the 75% refund amount
- [ ] Receipt: passenger badges from `status`, not hard-coded "Picked Up"
- [ ] Payments: safe to stop sending `riderId` / `driverId` / `amount`

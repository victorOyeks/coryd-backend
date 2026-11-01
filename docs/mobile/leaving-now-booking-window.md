# "I'm leaving now": departure picker and booking window

## Why this is changing
"I'm leaving now" posts the trip to leave 1 minute later, and booking closed at that time. A rider who searched a minute or two later still found the ride, but booking it failed with **"This ride has already departed"**. The server logs show exactly this.

## 1. Driver: choose when they're leaving
When the driver taps **I'm leaving now**, ask **"When are you leaving?"** with **In 15 min · In 30 min · In 45 min · In 60 min**.

- Send `departureTime = now + chosen minutes` (ISO-8601 UTC) in the usual post-route call. Nothing else in the request changes: `POST /trip/routes/drivers/{driverId}`.
- Don't send "now" or "now + 1 min" any more.

## 2. When riders can book (live on the backend now)
A trip can be booked until **whichever comes first**:
- the driver taps **Start Trip**;
- **10 minutes after `departureTime`**, if the driver hasn't started;
- the trip is full.

Once booking closes, the trip no longer appears in search, "Rides nearby" or the "nearby options" fallback. Riders already booked are unaffected. This applies to every trip, scheduled or "leaving now".

## 3. New field: `bookableUntil`
- **Where it appears:**
  - each result of `POST /trip/routes/search`;
  - the ride screen, `GET /trip/routes/{routePostId}`.
- **What it is:** the exact time booking closes, ISO-8601 UTC. It's `null` once the trip can't take bookings at all (started, finished or cancelled).
- **Use it** for any countdown or "book by" text, and to grey out the Book button.
- **Don't use `departureTime`** to decide whether a trip can still be booked.
- **Remove any timer that releases a seat hold or leaves the booking screen at `departureTime`.** The logs show the app releasing a rider's hold 0.15 seconds before the departure time.
- The ride screen's `bookable` and `unavailableReason` work as before: when `bookable` is false, show `unavailableReason` as-is.

## 4. Seat hold
- Near the closing time a hold is shorter than 4 minutes, so it ends with `bookableUntil`.
- **It's never shorter than 2 minutes,** so a rider who books at the last moment still has time to pay. Their hold can run up to 2 minutes past `bookableUntil`.
- Run the payment countdown on `holdExpiresAt` from the booking response (`POST /trip/bookings/routes/{routePostId}`), not a fixed 4 minutes.
- **Accepting a seat a favourite driver reserved** (`POST /trip/bookings/{bookingId}/reservations/accept`) follows the same rules:
  - after booking closes it fails with "This ride is no longer taking bookings";
  - its hold ends with the booking window.
- **If the driver taps Start while a rider is still paying,** the payment is refunded in full and the seat isn't given. The rider gets the usual "booking cancelled" push. Show the booking as cancelled; don't show a pickup code.

## 5. Driver screen after posting
- Show **"Riders can book until 5:45 PM"**, from `bookableUntil` on `GET /trip/routes/{routePostId}`, which returns it for the driver's own trip too.
- Show **"Tap Start Trip once everyone's aboard"**.
- **Starting the trip closes booking.** A rider still paying at that moment is refunded instead of seated. If seats are still free, or someone is mid-payment, confirm first: *"No more riders can book once you start. Start now?"*
- The driver is already notified when a rider books.

**Push changes (backend, nothing to do in the app):**
- A trip posted to leave within 30 minutes doesn't get the "Get ready to depart" reminder.
- Its "Your trip is due to leave" push waits until booking has closed.

## 6. Errors
Show the server's `message` as-is:
- **"This ride is no longer taking bookings"**: the driver started, or the 10 minutes after departure have passed. This replaces "This ride has already departed".
- **"This ride is full"**
- **"This ride has been cancelled"**
- **"This ride has already left"**: shown on the ride screen once the trip is under way.

## 7. Test checklist
- [ ] Each of 15 / 30 / 45 / 60 min posts `departureTime` that many minutes ahead.
- [ ] A rider can find and book the trip up to 10 minutes after the leaving time while the driver hasn't started.
- [ ] Once the driver taps Start, the trip is gone from search and booking it shows "This ride is no longer taking bookings".
- [ ] With no Start, the trip disappears 10 minutes after the leaving time.
- [ ] A full trip doesn't show in search.
- [ ] The countdown and Book button follow `bookableUntil`, not `departureTime`.
- [ ] The payment countdown follows `holdExpiresAt`; nothing releases the hold at `departureTime`.
- [ ] Booking in the last minute still gives at least 2 minutes to pay.
- [ ] Paying after the driver has tapped Start shows the booking as cancelled and refunded, with no pickup code.
- [ ] Accepting a reserved seat after booking has closed shows "This ride is no longer taking bookings".
- [ ] The driver sees "Riders can book until …" and the confirm-before-Start prompt.

The 10-minute limit is a server setting (`BOOKING_GRACE_MINUTES`), so changing it doesn't need an app release.

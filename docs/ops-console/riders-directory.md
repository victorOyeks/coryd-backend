# Ops Console: Riders Directory

Sidebar: **Riders → Riders Directory**.

Every rider, with their trips, reliability, rating, spend, reports and status. From a row, ops opens the rider's profile, then their trip history and receipts. They can suspend or reinstate the rider.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come with `success: false` and a `message` you can show as-is.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Header "N registered riders" and the tab badges | `GET /riders/counts` |
| Table: tabs, search, sorting, paging | `GET /riders` |
| **View**: the rider's profile | `GET /riders/{riderId}` |
| **Suspend Account** / reinstate | `POST /users/{riderId}/suspend` · `POST /users/{riderId}/reinstate` |
| **View Trip History** | `GET /riders/{riderId}/trips` |
| **Download receipt** | `GET /riders/{riderId}/trips/{bookingId}/receipt` |

---

## 2. Table: `GET /riders`

| Param | Default | Values |
|---|---|---|
| `status` | none = **All** | `ACTIVE` · `SUSPENDED` |
| `search` | none | Name, rider ref (`R-4F2A91`; the `R-` is optional), or phone (4 or more digits) |
| `sort` | newest sign-up first | `RIDER_REF` · `NAME` · `TRIPS` · `RETENTION` · `RATING` · `TOTAL_SPENT` · `REPORTS` · `STATUS` · `JOINED` |
| `direction` | `ASC` | `ASC` · `DESC` |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

**Sorting:** missing values (no trips yet, no rating yet) always go last, whichever direction you sort. An unknown `status` or `sort` gets a `400`.

```json
{
  "content": [
    {
      "rider": { "id": "4f2a91c0-…", "ref": "R-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "trips": 213,
      "reliabilityPercent": 91,
      "rating": 4.88, "ratingCount": 40,
      "totalSpent": 157000.00,
      "reportsFiled": 0,
      "status": "ACTIVE",
      "joinedAt": "2026-01-12T09:00:00Z"
    }
  ],
  "page": 0, "size": 10, "totalElements": 62, "totalPages": 7, "last": false
}
```

| Column | From |
|---|---|
| Action | **View** opens `GET /riders/{rider.id}` |
| Rider ID | `rider.ref` |
| Name, phone under it | `rider.name`, `rider.phone` |
| Trip | `trips`: trips completed |
| Retention | `reliabilityPercent`, as a bar; see section 7. `null` until they've completed, cancelled or missed a paid seat; show "–". |
| Rating | `rating`: what drivers rated them, out of 5. `null` means no ratings yet. |
| Total spent | `totalSpent`, in naira: the fares of their completed trips. Format it as "₦157k". |
| Report filed | `reportsFiled`: problems drivers reported on this rider's trips. "—" for 0. |
| Status | `status`: green "✓ ACTIVE" or red "✕ SUSPENDED" |
| Footer "Showing 1–10 of N" | `page`, `size`, `totalElements` |

**Suggested bar colours:** 85% and above teal, 70–84% amber, below 70% red. Those are the same as the drivers' reliability bar.

**Where the numbers come from:**
- Profile-service keeps one indexed row per rider, and the list is filtered, sorted and paged by the database.
- Trips, rating, reliability, reports and spend come from trip-service, within seconds of a trip ending, a rating or a report. Every rider is re-synced nightly.
- Suspensions come from identity-service.

### Tab badges: `GET /riders/counts?search=`

```json
{ "all": 62, "active": 52, "suspended": 10 }
```

- Send the same `search` as the table.
- The header "N registered riders in the pilot" is `all` with no search.

---

## 3. Rider profile: `GET /riders/{riderId}`

```json
{
  "rider": { "id": "4f2a91c0-…", "ref": "R-4F2A91", "name": "Emeka Okafor", "phone": "09036828299", "photoUrl": "…" },
  "status": "ACTIVE",
  "suspension": null,
  "account": { "ref": "R-4F2A91", "joinedAt": "2026-01-12T09:00:00Z" },
  "activity": {
    "totalTrips": 415, "rating": 4.88, "ratingCount": 312,
    "totalSpent": 103200.00, "reliabilityPercent": 97, "reportsFiled": 0
  }
}
```

| Shown | From |
|---|---|
| Header: name, ref | `rider.name`, `rider.ref` |
| Card: photo, short name ("Emeka O."), stars and count, phone | `rider.photoUrl`, `rider.name`, `activity.rating` (`ratingCount`), `rider.phone` |
| ✓ ACTIVE / ✕ SUSPENDED badge | `status` |
| Account: Rider ID, Joined | `account.ref`, `account.joinedAt` |
| Activity: Total Trips, Rating, Total Spent, Reliability Score, Reports Filed | `activity.*` |
| Suspended banner, if you add one | `suspension.suspendedAt`, `suspension.reason` |

An unknown rider gets `404 Rider not found`.

---

## 4. Suspend and reinstate

These are the same calls as for drivers.

**Suspend:** `POST /users/{riderId}/suspend`

```json
{ "reason": "Abusive to a driver" }
```

- The reason is required (up to 500 characters). It's kept on the account and shown back in `suspension.reason`.
- The rider can't sign in again, or book.
- Their upcoming seats are cancelled and refunded in full. A trip already under way is left to finish.
- **These cancellations don't count against their reliability.**

**Reinstate:** `POST /users/{riderId}/reinstate`, with no body.

**After either one,** refetch the profile and the counts.

**Buttons:**
- Show **Suspend Account** when `status` is `ACTIVE`.
- Show **Reinstate Account** when it's `SUSPENDED`. The design shows **Suspend Account** on a suspended rider; it should be **Reinstate Account** there.
- Ask for the reason in a dialog before suspending.

---

## 5. Trip History: `GET /riders/{riderId}/trips`

| Param | Default | Values |
|---|---|---|
| `search` | none | See below |
| `page` | `0` | 0-based |
| `size` | `10` | max `50` |

**Search** takes one of:
- **A place or a driver:** "ikorodu", "victoria", "emeka".
- **A date:** "11 July", "Wed, 11th July", "11/7", "2026-07-11" or "July".
- **A day of the week:** "wed" or "Wednesday".

Dates are matched in Lagos time. A word that is both a name and a date, like "Sunday", "May" or "June", matches either: trips on that day or in that month, and trips with a driver of that name.

```json
{
  "content": [
    {
      "bookingId": "b7e1…",
      "ref": "B-B7E1C2",
      "routePostId": "2f2f…",
      "status": "COMPLETED",
      "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
      "departureTime": "2026-07-11T06:00:00Z",
      "seatCount": 1,
      "fareTotal": 1800.00,
      "driver": { "id": "d9…", "name": "Emeka Okafor", "rating": 4.9, "ratingCount": 178 },
      "payment": {
        "status": "PAID", "method": "PAYSTACK", "cardType": "visa", "cardLast4": "4081", "reference": "…",
        "paidAt": "2026-07-10T18:00:00Z",
        "amountPaid": 1800.00, "refunded": 0, "refundPending": 0, "netPaid": 1800.00
      }
    }
  ],
  "page": 0, "size": 10, "totalElements": 23, "totalPages": 3, "last": false
}
```

| Shown | From |
|---|---|
| "Trip 001" label | The row's position (`page * size + index + 1`), or `ref` |
| Ikorodu → Victoria Island | `originLabel` → `destinationLabel` |
| Date | `departureTime`, as "Wed, 11th July" |
| Driver: name chip, stars and count | `driver.name`, `driver.rating` (`driver.ratingCount`). This is the driver's rating now, not at the time of the trip. |
| Status chip | `status`: `COMPLETED` "Completed", `CANCELLED_RIDER` / `CANCELLED_DRIVER` "Cancelled", `NO_SHOW` "No-show", anything else "Upcoming" / "In progress" |
| Amount, with "paid" under it | `fareTotal`. Under it, "paid" when `payment.refunded` is 0. Otherwise "₦1,350 refunded" (`payment.refunded`), or "refund pending" while `payment.refundPending` is more than 0. |

**Notes:**
- Only bookings the rider paid for are listed. Seats held but never paid for aren't trips.
- Newest trip first.
- `payment` is `null` if payment-service has no record or can't be reached. Show `fareTotal` alone then.

---

## 6. Receipt: `GET /riders/{riderId}/trips/{bookingId}/receipt`

**The backend returns the receipt's contents, not a PDF.** Render it in the console and save or print it as a PDF there.

```json
{
  "bookingId": "b7e1…",
  "ref": "B-B7E1C2",
  "status": "COMPLETED",
  "rider": { "id": "4f2a91c0-…", "ref": "R-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
  "route": {
    "routePostId": "2f2f…",
    "originLabel": "Ikorodu", "destinationLabel": "Victoria Island",
    "pickupLabel": "Ikorodu Garage", "dropoffLabel": "Victoria Island",
    "departureTime": "2026-07-11T06:00:00Z"
  },
  "bookedAt": "2026-07-10T17:58:00Z",
  "paidAt": "2026-07-10T18:00:00Z",
  "pickedUpAt": "2026-07-11T06:04:00Z",
  "completedAt": "2026-07-11T06:52:00Z",
  "driver": { "id": "d9…", "ref": "D-D9A1B2", "name": "Emeka Okafor", "vehiclePlate": "LND-441-AA" },
  "seatCount": 1,
  "farePerSeat": 1800.00,
  "fareTotal": 1800.00,
  "payment": { "status": "PAID", "method": "PAYSTACK", "cardType": "visa", "cardLast4": "4081", "reference": "…",
               "paidAt": "2026-07-10T18:00:00Z", "amountPaid": 1800.00, "refunded": 0, "refundPending": 0, "netPaid": 1800.00 }
}
```

**Fields:**
- The driver's name and plate are as they were when the seat was booked.
- `pickupLabel` / `dropoffLabel` are where the rider got on and off; they're the route's ends when the rider used those.
- `pickedUpAt` and `completedAt` are `null` for a trip that didn't happen.

**A cancelled trip's receipt** shows `amountPaid`, `refunded` and `netPaid`. A late rider cancellation is refunded 75%.

**Errors:** a booking that isn't this rider's, or doesn't exist, gets `404 Trip not found for this rider`.

---

## 7. What doesn't fit how the app works

- **"Retention" and "Reliability Score" are the same number.**
  - The app has no per-rider retention measure. The table's Retention column and the profile's Reliability Score both use `reliabilityPercent`.
  - It's the share of paid seats the rider completed, rather than cancelled or missed (no-show).
  - If "retention" was meant to be something else, such as how many weeks they've ridden since their first trip, say so; it's a backend addition.
- **Rider ID:** it's `R-` plus the first six characters of the rider's id (`R-4F2A91`), not a running number (`R-001`).
- **Total spent** counts the fares of completed trips only. Money refunded for cancelled trips isn't in it.
- **Reports filed** counts problems **drivers reported** on this rider's trips. It doesn't count reports the rider made about drivers. If both are wanted, say so.
- **Rating:**
  - It's how drivers rated the rider. Drivers rate riders after a trip, so a new rider has no rating (`null`).
  - The profile card's "4.9 (312)" and the Activity "Rating ★ 4.88" are the same field; show the same number in both.
- **Suspended riders:** the design's profile shows **Suspend Account** even when the rider is suspended. Use **Reinstate Account** there.
- **Download receipt:** the backend doesn't make PDFs. The console renders the receipt data and saves it as a PDF.
- **Riders who never finished sign-up:** someone who signed up but never saved their name isn't listed until they do. Their rider profile only exists from then.
- **Drivers who also ride:**
  - An account holding both roles appears in both directories.
  - A driver-only account isn't listed here, even if they saved an emergency contact.

---

## 8. Checklist

- [ ] Header total and tab badges from `/riders/counts`, using the same `search` as the table.
- [ ] Table from `/riders`: tabs (`status`), search, sortable columns (`sort` + `direction`), page size and paging.
- [ ] Retention bar from `reliabilityPercent`; dashes for `null` numbers; "₦157k" from `totalSpent`.
- [ ] Profile from `/riders/{riderId}`: card, status badge, account, activity.
- [ ] **Suspend Account** (with a reason dialog) and **Reinstate Account**, refreshing afterwards.
- [ ] Trip History from `/riders/{riderId}/trips`, with search and paging; "paid" or "refunded" from `payment`.
- [ ] **Download receipt** from `/riders/{riderId}/trips/{bookingId}/receipt`, rendered and saved as a PDF in the console.

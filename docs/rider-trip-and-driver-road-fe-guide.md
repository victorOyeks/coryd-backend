# Rider's own trip, the driver's road, stop order and account deletion: FE guide

This covers the backend changes from 25 October 2026. Every endpoint below goes through the gateway as usual (`/trip/...`, `/identity/...`). All new response fields are additions, so the current app keeps working. Fields marked **must** are needed to fix bugs seen in testing.

## At a glance

| # | What | App | Change |
| --- | --- | --- | --- |
| 1 | [Search list shows the rider's own trip](#1-search-list-shows-the-riders-own-trip) | Rider | Show `pickup` / `dropoff` (now with `label`) on each card, not only after a tap. Send place names when searching. |
| 2 | [Book with the rider's own pickup and drop-off](#2-book-with-the-riders-own-pickup-and-drop-off) | Rider | **Must.** Send the card's `pickup` / `dropoff` with their labels, never the driver's route ends. |
| 3 | [Booking screens show the rider's own trip](#3-booking-screens-show-the-riders-own-trip) | Rider | New pickup/drop-off fields on Pay Now, Booking Confirmed, active trip and upcoming trips. |
| 4 | [Driver picks the road](#4-driver-picks-the-road) | Driver | New road picker when posting a route or a regular route. |
| 4d | [Suggested price](#4d-suggested-price) | Driver | Prefill the fare with a suggestion the driver can keep or change. |
| 5 | [Passenger list in stop order](#5-passenger-list-in-stop-order) | Driver | Sort passengers by two new distance fields. |
| 6 | [Delete account](#6-delete-account) | Both | New Settings action. |
| 7 | [Name locked once verified](#7-name-locked-once-verified) | Both | After the NIN check passes, the name can't be edited. |
| 8 | [Drivers: NIN by number, no selfie](#8-drivers-nin-by-number-no-selfie) | Driver | The NIN step becomes a number-only lookup; the face is matched on the licence check. Riders unchanged. |

### The problem we saw in testing

The driver posted **Ikorodu → Victoria Island**. A rider searched **Ketu → Osborne** and didn't find the ride, because Google had drawn the road through Obalende. Searching **Ketu → Obalende** found it, but:

- The results card only showed Ikorodu → Victoria Island. The rider's own Ketu → Obalende appeared only after tapping in.
- After booking, the rider's confirmation said Ikorodu → Victoria Island.
- The driver's passenger list showed the rider getting in at Ikorodu, not Ketu.

Sections 1–3 fix the rider and passenger-list side. Section 4 fixes the missed search: the driver picks the road they actually drive, and riders are matched along that road.

---

## 1. Search list shows the rider's own trip

`POST /trip/routes/search`

**Request:** send the place names the rider picked. They are optional, but without them there are no labels to show or to book with.

```json
{
  "originLat": 6.5703, "originLng": 3.3874,
  "destinationLat": 6.4497, "destinationLng": 3.4089,
  "desiredDepartureTime": "2026-10-26T06:00:00Z",
  "originLabel": "Ketu",
  "destinationLabel": "Osborne"
}
```

**Each result card:**

```json
{
  "routePostId": "…",
  "originLabel": "Ikorodu",
  "destinationLabel": "Victoria Island",
  "departureTime": "2026-10-26T06:00:00Z",
  "...": "existing fields unchanged",

  "alongTheWay": true,
  "pickup":  { "lat": 6.5703, "lng": 3.3874, "walkMeters": 180, "time": "2026-10-26T06:20:00Z", "label": "Ketu" },
  "dropoff": { "lat": 6.4497, "lng": 3.4089, "walkMeters": 220, "time": "2026-10-26T06:55:00Z", "label": "Osborne" },
  "viaSummary": "Ojota, Maryland, Osborne"
}
```

| Field | New? | Meaning |
| --- | --- | --- |
| `originLabel`, `destinationLabel` | — | The **driver's** whole route. |
| `pickup`, `dropoff` | — | Where **this rider** gets in and out, on the driver's road. `null` for an end-to-end match. |
| `pickup.label`, `dropoff.label` | **New** | The place names from the search request. `null` if the search didn't send them. |
| `viaSummary` | **New** | The road the driver picked, in words (section 4). `null` when the driver didn't pick one. |

**On the list card** (not only on the details screen), when `pickup` is present:

> **Ketu → Osborne** · around 7:20
> on Ikorodu → Victoria Island · via Ojota, Maryland, Osborne

Lead with the rider's trip. Show the driver's route and `viaSummary` as smaller context. When `pickup` is `null`, show the card as before.

**Ride details** (`GET /trip/routes/{routePostId}?riderLat=&riderLng=`) also has `viaSummary`, and its `overviewPolyline` is now the driver's picked road. Keep using the card's `pickup` / `dropoff` for this rider's pins.

---

## 2. Book with the rider's own pickup and drop-off

`POST /trip/bookings/routes/{routePostId}`. **Must.**

```json
{
  "seatCount": 1,
  "pickupLabel": "Ketu",
  "pickupLat": 6.5703,
  "pickupLng": 3.3874,
  "dropoffLabel": "Osborne",
  "dropoffLat": 6.4497,
  "dropoffLng": 3.4089
}
```

- Fill `pickupLabel` / `pickupLat` / `pickupLng` from the card's `pickup`, and the three `dropoff*` fields from the card's `dropoff`.
- If a label is `null`, use the nearest landmark or the rider's searched place name.
- For an end-to-end match (`pickup` is `null`), keep what you send today, and leave out the drop-off fields.
- **Never send the route's `originLabel` / `destinationLabel` as the rider's labels.** The driver's passenger list shows exactly what you send. That is how the driver ended up seeing "Ikorodu" for a rider getting in at Ketu.

This is unchanged: a pickup more than a short walk from the driver's road is refused with `400` *"Pick a pickup point on the driver's route"*.

---

## 3. Booking screens show the rider's own trip

On these responses, `originLabel` / `destinationLabel` are the **driver's whole route**. The new fields carry the rider's own trip.

**`GET /trip/bookings/{bookingId}`** (Pay Now, Booking Confirmed) and **`GET /trip/bookings/riders/{riderId}/active`** (active trip):

| Field | Meaning |
| --- | --- |
| `pickupLabel`, `pickupLat`, `pickupLng` | Where this rider gets in. |
| `dropoffLabel`, `dropoffLat`, `dropoffLng` | Where they get out. Filled with the route's end when they ride all the way, so these are never empty. |
| `overviewPolyline` | Now the driver's actual road when we have it. Put the pickup and drop-off pins on it. |

**`GET /trip/bookings/riders/me/upcoming`:** each item gets a new `dropoffLabel`. `pickupLabel` was already there.

Show it as **"Ketu → Osborne"**, with "on Ikorodu → Victoria Island" underneath.

---

## 4. Driver picks the road

There is more than one way from Ikorodu to Victoria Island. Until now we always used Google's first suggestion, so riders on the road the driver really takes (Ojota, Maryland, Osborne…) didn't find the ride. Now the driver picks the road while posting. Search matches riders against that road, and riders see it on the map.

### 4a. Get road choices: `POST /trip/maps/route-options` (signed in)

Use it in two ways:

**Choose between Google's roads.** Send only the two ends. You get Google's usual road first, plus up to two others. Often there is only one; then show it selected, and offer "I go through…" as the way to change it. Google decides how many roads to offer from live traffic, so the server keeps a choice of two or more for a day: reopening the screen for the same two ends shows the same roads. A single road isn't kept, so the next open may come back with more.

```json
{
  "originLat": 6.6194, "originLng": 3.5105,
  "destinationLat": 6.4281, "destinationLng": 3.4219
}
```

**Pass through places the driver names.** Add `via`, in the order the driver passes them (up to 8). You get one road through them, in that order. Use the existing place search (`/trip/maps/places/autocomplete` → `/trip/maps/places/details`) to turn a typed place into coordinates.

```json
{
  "originLat": 6.6194, "originLng": 3.5105,
  "destinationLat": 6.4281, "destinationLng": 3.4219,
  "via": [
    { "lat": 6.5874, "lng": 3.3796 },
    { "lat": 6.5711, "lng": 3.3667 },
    { "lat": 6.4497, "lng": 3.4089 }
  ]
}
```

**Response:** a list of roads, each the same shape as `/trip/maps/route-preview`.

```json
[
  {
    "distanceKm": 34.2,
    "durationMinutes": 68,
    "durationText": "1 hour 8 mins",
    "viaSummary": "Ikorodu Rd and Third Mainland Bridge",
    "overviewPolyline": "o~lf@}xpT…"
  }
]
```

| Error | When |
| --- | --- |
| `400` *"No driving road goes through those places - try other ones"* | Google can't make a road through the `via` points. |
| `400` *"Pick at most 8 places to pass through"* | More than 8 `via` points. |
| `400` *"Maps service is temporarily unavailable…"* / *"Unable to load roads…"* | Maps is down. Let the driver post without picking a road (Google's usual one). |

### Suggested screen

After the driver sets the start and destination:

1. Call `route-options` with no `via`. Draw every road on the map, with the first one selected. Show each as a chip: `viaSummary · durationText · distanceKm km`.
2. The driver taps a road to select it, **or** taps **"I go through…"** and adds places in order. Each change calls `route-options` again with `via`, and the map shows the single road it returns.
3. The road on screen when they continue is the one that gets posted.

### 4b. Post the route with the picked road

`POST /trip/routes/drivers/{driverId}`. Two new optional fields:

```json
{
  "...": "existing fields unchanged",
  "routePolyline": "o~lf@}xpT…",
  "viaSummary": "Ojota, Maryland, Osborne"
}
```

| Field | Meaning |
| --- | --- |
| `routePolyline` | The selected road's `overviewPolyline`, unchanged. Leave it out to use Google's usual road, as before. |
| `viaSummary` | **Required when `routePolyline` is sent**, max 255 characters. Riders see it. Send the names of the driver's `via` places ("Ojota, Maryland, Osborne"), or the chosen option's `viaSummary` when they picked one of Google's roads. |

The backend works out the trip's driving time from the picked road, so a longer road gets a longer time. The response (the posted route) now includes `viaSummary`.

| Error | When |
| --- | --- |
| `400` *"Send viaSummary with the road you picked - riders see it on the ride"* | `routePolyline` sent without `viaSummary`. |
| `400` *"The road you picked doesn't start and end at this route's pickup and destination - pick it again"* | The start or destination changed after the road was picked. Call `route-options` again whenever either end changes. |
| `400` *"The road you picked couldn't be read - pick it again"* | `routePolyline` was altered. |

### 4c. Regular routes

`POST /trip/routes/templates` and `PUT /trip/routes/templates/{templateId}` take the same two fields, `routePolyline` and `viaSummary`, with the same rules. Every trip the regular route posts follows that road. `GET /trip/routes/templates` returns both fields, so the edit screen can draw the saved road. Send them again on every `PUT` to keep the road: leaving them out goes back to Google's usual road.

### 4d. Suggested price

`POST /trip/routes/fare-suggestion` (signed in)

Call it once the start, destination and road are set, and again whenever any of them change. Prefill the fare field with the suggestion, labelled **"Suggested: ₦4,000"**. The driver can keep it or type their own, and whatever they post is the fare.

```json
{
  "originLat": 6.6194, "originLng": 3.5105,
  "destinationLat": 6.4281, "destinationLng": 3.4219,
  "routePolyline": "o~lf@}xpT…"
}
```

`routePolyline` is optional: send the picked road (section 4a) if there is one. Without it, the suggestion uses Google's usual road.

```json
{ "distanceKm": 39.6, "ratePerKm": 100, "suggestedFarePerSeat": 4000 }
```

- **How it's worked out:** the length of the driver's **whole** road × `ratePerKm`, rounded to the nearest ₦100, and never below ₦500.
- **Same for every rider:** the fare is per seat, wherever the rider gets in or out. Someone joining at Ketu pays the same as someone riding from Ikorodu.
- **Rate can change:** ops can change the rate without an app release, so always show the `suggestedFarePerSeat` the backend returns. Don't calculate it in the app.
- **If it fails** (Maps down): leave the fare field empty and let the driver type one.
- **A longer road suggests more:** adding "I go through…" places usually lengthens the road, and the suggestion rises with it. In testing, Ikorodu → VI was 41.3 km (₦4,100) on Google's road and 52.5 km (₦5,300) through Ojota, Maryland and Osborne. Refresh the suggestion after the road changes, so the driver sees why.

The existing `GET /trip/routes/suggested-fare` (what other drivers charged on the same route names) is unchanged.

### Where the picked road shows up

| Screen | What changes |
| --- | --- |
| Rider search card | `viaSummary` (section 1) |
| Ride details | `viaSummary`, and `overviewPolyline` is the picked road |
| Booking screens | `overviewPolyline` is the picked road (section 3) |
| Driver's map before any pickup (`GET /trip/routes/{routePostId}/route-preview`) | The picked road |

Once riders have booked, the live map routes the car through their pickups as before.

---

## 5. Passenger list in stop order

Driver app: the manifest (`GET /trip/bookings/routes/{routePostId}`), active trip and trip completion. Each passenger (`BookingSummary`) has:

| Field | Meaning |
| --- | --- |
| `pickupLabel`, `dropoffLabel` | What the rider app sent when booking (section 2). `dropoffLabel` `null` means the route's end. |
| `pickupAlongRouteMeters` | **New.** How far along the driver's road the pickup is, in metres from the start. `0` means they board at the start. |
| `dropoffAlongRouteMeters` | **New.** The same for where they get out: the road's length if they ride to the end, and never before their own pickup. |

Sort pickups by `pickupAlongRouteMeters` and drop-offs by `dropoffAlongRouteMeters`. Both are measured along the road the driver actually drives, which is now the picked road, so the order stays right on roads that bend back. Both are `null` only for old routes with no saved road. Keep the current order then.

---

## 6. Delete account

Riders and drivers can delete their own account (Settings → Delete account). The App Store and Play Store require this.

**Ask first:** "Delete your account? Your profile, ID checks and saved details are erased for good. Your upcoming trips are cancelled and refunded. This can't be undone."

`DELETE /identity/auth/me` (signed in)

```json
{ "confirm": true }
```

- **`200`** *"Your account has been deleted"*: sign the person out and clear everything stored on the phone.
- **Erased:** account, profiles, NIN and licence checks, documents, vehicles, emergency contacts, saved places, favourites, blocks, notify-me requests, cards, payout details, notifications.
- **Kept with no name or phone attached:** past trips, bookings, payments and ratings.
- Upcoming trips and bookings are cancelled, and riders are refunded.
- The phone number is free again. Signing up with it starts a brand-new account.

| Error | When | Show |
| --- | --- | --- |
| `400` *"Confirm that you want to delete your account"* | `confirm` not `true` | Shouldn't happen if you ask first. |
| `400` *"This account can't be deleted yet: …"* | On a trip right now, owes CoRyd money, or riders' payments are still settling. The message lists every reason. | The message as-is. |
| `400` *"Your account is suspended - contact CoRyd support to close it"* | Suspended account | The message, with a link to support. |
| `502` *"We couldn't check your trip/payment records just now - please try again in a few minutes"* | A service couldn't be reached | The message, with a Try again button. |

---

## 7. Name locked once verified

People still type their first and last name, **as on their NIN**, before the NIN check. The check compares it with the NIMC record:

- **Close enough:** approved, and **the name they typed is kept** (we don't replace it with NIMC's spelling).
- **Too different:** goes to ops, as before. If ops approves, the typed name is kept.

**Once the NIN check is approved, the name can't be changed in the app.**

**`GET` / `PUT /profile/riders/me`** and **`GET` / `PUT /profile/drivers/me`** have a new field:

| Field | Meaning |
| --- | --- |
| `nameVerified` | `true` once the NIN check is approved. |

When `nameVerified` is `true`:
- Show the name as verified (e.g. "Victor Oyekanmi ✓ Verified with NIN") and make the first and last name fields **read-only**.
- Keep sending the profile as you do today. A different name in the request is **ignored**, not refused, so saving an email or photo still works. Show the name that comes back in the response.
- If someone's name is wrong or has legally changed, they contact support.

Before the check (`nameVerified` is `false`), the name is editable as today. Tell people to type it exactly as on their NIN, since a different name sends their check to ops and slows them down.

---

## 8. Drivers: NIN by number, no selfie

To cut Smile ID costs, each driver now does **one** face match instead of two:

| | Rider app | Driver app |
| --- | --- | --- |
| NIN | NIN + live selfie, as today | **NIN number only**, no camera |
| Licence | — | Licence + live selfie, as today |
| Verified when | The NIN check passes | **Both** pass, and the NIN record and the licence are the same person (name, and date of birth where both have one) |

The **NIN step comes first**: the licence check won't open until the NIN is confirmed (or with ops).

### Which flow a driver gets

`GET /profile/drivers/me` has new fields:

| Field | Meaning |
| --- | --- |
| `ninLookup` | `true`: use the number-only lookup below. `false`: the older NIN + selfie check (only while the backend switch is off for older app builds). |
| `ninStatus` | `PENDING` (ask for the NIN), `CHECKING` (result on its way), `MATCH` (done), `IN_REVIEW` (with ops), `REJECTED` (ops turned it down; they can try again). |
| `ninLast4` | Last four digits of the NIN they gave, for "NIN ending 4455". |
| `ninRetryReason` | Why the last lookup didn't work, while `ninStatus` is `PENDING`: `NIN_NOT_FOUND` ("We couldn't find that NIN - check the number") or `TRY_LATER`. |
| `ninTriesLeft` | Lookups left (3 to start with). |

A driver who already passed the NIN + selfie check (for example a verified rider who becomes a driver) shows `ninStatus: MATCH` and goes straight to the licence.

### The lookup: `POST /profile/drivers/me/nin-lookup`

```json
{ "nin": "22233344455", "consent": true }
```

**Not instant (a change from your draft).** Smile ID answers this lookup on our webhook, like every other check, usually within a few seconds. The endpoint replies straight away:

```json
{ "ninStatus": "CHECKING", "ninLast4": "4455" }
```

Then poll `GET /profile/drivers/me` every 2-3 seconds until `ninStatus` is no longer `CHECKING`, and show a "Checking your NIN…" state meanwhile. Sending the request again while it's checking does nothing new (it just answers `CHECKING`).

It can also answer `"ninStatus": "IN_REVIEW"` straight away, when the NIN is already on another account (ops looks at it; no lookup is made).

| Error | When | Show |
| --- | --- | --- |
| `400` *"NIN must be 11 digits"* | Wrong format. Check it in the app first, too: it's free. | Inline on the field |
| `400` *"Agree to the NIN check to continue"* | `consent` not `true` | Inline |
| `400` *"Give it a few seconds before trying again"* | Within 30 seconds of the last try | Disable the button for 30 seconds after each try |
| `400` *"NIMC had no record of that NIN when you tried it - check the number and try again"* | The same NIN that already came back not found | Inline |
| `400` *"You've used all your NIN tries - contact CoRyd support…"* | 3 lookups used | The message, with a link to support |
| `400` *"Your NIN is already confirmed"* / *"Your NIN is with our team…"* | Already done, or with ops | Refresh the profile |
| `400` *"Drivers confirm their NIN by number and verify their face on the licence check…"* | The driver app calling the old NIN + selfie session | Shouldn't happen once updated |
| `400` *"Confirm your NIN first - it's the step before your licence"* | Licence check opened before the NIN | Send them to the NIN step |
| `502` *"Identity checks are unavailable right now…"* | Smile ID couldn't be reached | The message, with Try again |

**Each lookup costs money**, so: validate the 11 digits before sending, disable the button while checking and for 30 seconds after, and don't retry automatically.

### What the driver sees

1. **NIN screen** (when `ninStatus` is `PENDING` or `REJECTED`): NIN field, consent checkbox, Continue. On `NIN_NOT_FOUND`, show the message and the tries left.
2. **Checking** (`CHECKING`): a short spinner while polling.
3. **`MATCH`**: on to the licence check (unchanged).
4. **`IN_REVIEW`**: "Our team is checking your NIN - we'll let you know." The licence check can still be done meanwhile.
5. **Verified**: once both are done, `GET /profile/identity-verification/me` shows `APPROVED`, `GET /identity/auth/onboarding-status` shows `identityVerified: true`, and they can post routes. A driver verified this way can also book rides without another check.

---

## Checklist

**Rider app**
- [ ] Search sends `originLabel` / `destinationLabel`.
- [ ] Results card shows the rider's trip (`pickup.label` → `dropoff.label`, time), the driver's route and `viaSummary`.
- [ ] Booking sends the card's `pickup` / `dropoff` with their labels. Never the route's ends.
- [ ] Pay Now, Booking Confirmed and active trip lead with `pickupLabel` → `dropoffLabel`, with pins on `overviewPolyline`.
- [ ] Upcoming trips show `pickupLabel` → `dropoffLabel`.
- [ ] Settings → Delete account.
- [ ] Profile: name read-only with a "Verified with NIN" mark when `nameVerified` is `true`.

**Driver app**
- [ ] Post-route road picker: `route-options` with no `via` (choose a road) and with `via` ("I go through…").
- [ ] Post sends `routePolyline` + `viaSummary`. Pick the road again when either end changes.
- [ ] Regular route create/edit: same road picker, saved road drawn from `GET /trip/routes/templates`.
- [ ] Fare field prefilled from `fare-suggestion`, labelled "Suggested", editable. Refreshed when the ends or the road change.
- [ ] Passenger list sorted by `pickupAlongRouteMeters` / `dropoffAlongRouteMeters`.
- [ ] Settings → Delete account.
- [ ] Profile: name read-only with a "Verified with NIN" mark when `nameVerified` is `true`.
- [ ] NIN step: number + consent only when `ninLookup` is `true`; poll `GET /profile/drivers/me` while `CHECKING`; tries left and the not-found message shown; button disabled while checking and for 30 s after.
- [ ] Licence check offered only once `ninStatus` is `MATCH` or `IN_REVIEW`.

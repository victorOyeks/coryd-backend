# FE guide: safety, identity (NIN), along-the-way matching and pilot update

Everything the rider and driver apps need for this round of backend changes: what's new, what changed, and what the app must do. The Ops Console calls are in the [appendix](#appendix-ops-console).

All paths go through the gateway (`https://api-dev.coryd.ng` on dev) with the usual `Authorization: Bearer <accessToken>`. Responses keep the usual envelope: `{ success, message, data }`. Errors come back as `success: false` with a human-readable `message` you can show as-is.

## At a glance

| Area | App | What the app must do |
| --- | --- | --- |
| [Pickup must be on the driver's road](#1-pickup-must-be-on-the-drivers-road) | Rider | **Breaking.** Send the search card's `pickup` point when booking. |
| [Ratings start empty](#2-ratings-start-empty) | Both | **Breaking.** Show "New" when there are no ratings. |
| [Suspended accounts](#3-suspended-accounts) | Both | Handle the new 401 on login/refresh and 400 on post/book. |
| [Along-the-way rides](#along-the-way-rides-search-and-booking) | Rider, Driver | Show `alongTheWay`, `pickup`, `dropoff`; driver sees drop-offs. |
| [Tighter matching](#tighter-matching-windows) | Rider | Nothing required; fewer, more useful results. |
| [NIN required for everyone](#4-nin-required-for-everyone) | Rider, Driver | **Breaking.** New required onboarding step; booking/posting closed until approved. |
| [Identity check flow](#identity-check-nin--selfie-everyone) | Rider, Driver | **Smile ID SDK** (needs Expo 56+): NIN screen, live selfie, status and retry screens; "Verified rider" badge for drivers. |
| [Driver's licence check](#drivers-licence-check-drivers) | Driver | Licence photo + selfie through the Smile ID SDK instead of uploading a photo; status and retry screens. |
| [Driver SOS and trip sharing](#driver-sos-and-trip-sharing) | Driver | Two new buttons on the active trip. |
| ["Are you OK?" checks](#are-you-ok-safety-checks) | Both | New push + answer screen; background location for drivers. |
| [Blocking](#blocking) | Both | Block / unblock buttons and a blocked list. |
| [Regular routes](#regular-routes-drivers) | Driver | New screens to set a trip once. |
| [Notify-me duplicates](#notify-me) | Rider | Handle a new 400. |
| [Ride details reasons](#ride-details-why-a-ride-cant-be-booked) | Rider | Show the new `unavailableReason` values. |
| ["How did you hear about us?"](#how-did-you-hear-about-us-at-signup) | Both | Optional question on signup. |
| [Push notifications](#push-notifications) | Both | New `SAFETY_CHECK` type. |
| [Beta waitlist form](#website-beta-waitlist-form) | Website | New public endpoint for the "Join the CoRyd beta" page. |

Every response change is **additive** (new fields only), so current app versions keep working, apart from the four behaviour changes below.

---

## Behaviour changes to handle first

### 1. Pickup must be on the driver's road

Drivers no longer leave their route to collect riders. When booking, the pickup must be within a **1 km walk of the driver's road**, otherwise:

`400` *"Pick a pickup point on the driver's route"*

**Do:** book with the `pickup` point from the search card (see [Along-the-way rides](#along-the-way-rides-search-and-booking)). If the card has no `pickup` (an end-to-end match), use the route's start.

> Release note: the backend can ship with this rule switched off (`MATCHING_ALONG_ROUTE_ENABLED=false`) until this app version is out. Tell the backend team when it's live.

### 2. Ratings start empty

New users no longer get an automatic 5-star rating. Ratings are now only real ones. Wherever you show a rating (`ratingAvg`/`ratingCount`, `riderRatingAvg`/`riderRatingCount`, `GET /trip/bookings/users/{userId}/rating-summary`):

- `ratingCount` is `0` or the average is `null` → show **"New"**, not stars, `0.0` or a blank.

### 3. Suspended accounts

Ops can now suspend a rider or driver.

| Where | Response | Do |
| --- | --- | --- |
| Login, verify OTP, refresh token, add/switch role | `401` *"This account has been suspended. Contact CoRyd support."* | Sign the user out and show the message. Don't retry refresh. |
| Post a route, book a seat (with a still-valid token) | `400` *"This account is suspended. Contact CoRyd support."* | Show the message. |

A suspended user's upcoming routes and seats are cancelled automatically. Affected riders get the normal "Booking cancelled" push (*"Your booking couldn't go ahead and has been cancelled. Your payment has been refunded."*).

---

### 4. NIN required for everyone

No rider or driver can use CoRyd without a verified NIN. Until their [identity check](#identity-check-nin--selfie-everyone) is approved:

- riders get `400` *"Verify your identity to book a ride"* when booking;
- drivers get `400` *"Verify your identity to post a route"* when posting.

Existing users who haven't verified hit these too, so the identity step must be reachable **for existing accounts**, not only at signup. On app open, check `GET /identity/auth/onboarding-status` and send anyone with `identityVerified: false` to it.

---

## Along-the-way rides (search and booking)

A rider now also finds trips that **pass them**, not only ones starting and ending near them: their start and end are each within a **1 km walk** of the driver's road, and the car reaches their start first. The rider walks to the road; the driver doesn't detour. The fare is the full seat price, and the seat is held for the whole trip.

### Search: `POST /trip/routes/search`

Request unchanged. Each result card has three new fields:

```json
{
  "routePostId": "…",
  "originLabel": "Ikorodu",
  "destinationLabel": "Victoria Island",
  "departureTime": "2026-10-16T06:00:00Z",
  "farePerSeat": 2500,
  "pickupEtaMinutes": 12,
  "...": "existing fields unchanged",

  "alongTheWay": true,
  "pickup":  { "lat": 6.5703, "lng": 3.4851, "walkMeters": 180, "time": "2026-10-16T06:15:00Z" },
  "dropoff": { "lat": 6.4702, "lng": 3.4349, "walkMeters": 220, "time": "2026-10-16T06:45:00Z" }
}
```

| Field | Meaning |
| --- | --- |
| `alongTheWay` | `true` when the rider joins part-way (they don't match the route's ends). Label it, e.g. **"Passes near you"**. |
| `pickup` | Where to meet the car on its road, how far that is from where they searched, and roughly when the car gets there. `null` on end-to-end matches with no pickup point worked out. |
| `dropoff` | Same, for getting out. |

- Results are sorted by when the car reaches the rider.
- `pickupEtaMinutes` is the drive time to the `pickup` point when there is one.
- `time` is an estimate from the route's driving time. Show it as "around 7:15".
- Show the pickup and drop-off on the map, with walking directions from the rider's location to `pickup`.

### Booking: `POST /trip/bookings/routes/{routePostId}`

Three new **optional** fields:

```json
{
  "seatCount": 1,
  "pickupLabel": "Ketu bus stop",
  "pickupLat": 6.5703,
  "pickupLng": 3.4851,
  "dropoffLabel": "Obalende",
  "dropoffLat": 6.4702,
  "dropoffLng": 3.4349
}
```

- Send the card's `pickup` as `pickupLat`/`pickupLng`, with a label (nearest landmark or the place name).
- Send the card's `dropoff` as `dropoffLabel`/`dropoffLat`/`dropoffLng`. Omit them for end-to-end rides.

### Driver side

Each passenger (`BookingSummary`: manifest `GET /trip/bookings/routes/{routePostId}`, active trip, trip completion) has new fields:

| Field | Meaning |
| --- | --- |
| `dropoffLabel`, `dropoffLat`, `dropoffLng` | Where the rider gets out; `null` = the route's end. |
| `riderVerified` | See [Identity check](#identity-check-nin--selfie-everyone). |

Show pickups and drop-offs in order along the route.

### Tighter matching windows

End-to-end matches now need the trip to leave **within 60 minutes** of the rider's time (was 3 hours), and start and end **within 2 km** of theirs (was 4 km). Riders further out are found along the way instead. No app change needed; expect fewer, more usable results. The "No rides found" fallback (`/trip/routes/search/nearby-options`) is unchanged.

---

## Identity check: NIN + selfie (everyone)

**Nobody rides or drives without a verified NIN.** Riders and drivers both give their **NIN** and take a **live selfie** during onboarding. The check is run by **Smile ID** (Biometric KYC). It looks up the NIN with NIMC, runs a liveness check on the selfie, compares the face to the NIMC photo, and the backend then matches the NIMC name to the profile name. Anything it can't settle is reviewed by ops. Only ops ever rejects, with a reason the user sees.

It's **one check per person**: someone who is both a rider and a driver verifies once, in whichever app they onboard first. Drivers also do their [licence check](#drivers-licence-check-drivers) (Smile ID) and their vehicle and document checks (ops); the NIN check is on top of those.

### What the app needs

- **Smile ID's v12 mobile SDK**, React Native package [`@smileid/usesmileid`](https://docs.usesmileid.com) and its provider packages, plus a selfie ML provider.
  - **Requires Expo SDK 56+** (React Native 0.83+, iOS 16.4+, Android `minSdk` 24). The app is on Expo 54, so upgrade first.
  - It's a native module: it needs a new build (not an over-the-air update) and doesn't run in Expo Go.
- **Don't use the older SDK.** Ignore any guide mentioning `@smile_identity/react-native-expo`, `smile_config.json`, `SmileID.initialize()`, `SmileIDBiometricKYCView` or `ResultCode` values like `0810`. That's v10/v11, and Smile ID says v11 code won't compile against v12.
- **No keys in the app.** The backend gives the app a short-lived token for each check (below). The partner ID and the sandbox/live switch come with it, so the app doesn't hard-code them.

### Flow (same in both apps)

```
App                                   CoRyd backend                      Smile ID
 │ POST /profile/identity-verification/session                              │
 │ { nin, consent: true } ───────────────▶ opens a job, gets a token ──────▶│
 │ ◀──── { jobId, userId, token, partnerId, sandbox, country, idType } ──── │
 │ Smile ID SDK: consent, live selfie, submit ─────────────────────────────▶│
 │ onResult → poll GET /profile/identity-verification/me every 3 s          │
 │                                        │◀──── result (signed webhook) ───│
 │ ◀──── status / retryReason             │                                 │
```

1. **Name first.** The first and last name (as on the NIN) must be saved on the profile for the role they're in: `PUT /profile/riders/me` or `PUT /profile/drivers/me`.
2. **NIN + consent screen** (as today): consent text says the NIN and selfie are checked with NIMC through our verification provider.
3. **Open the check:** `POST /profile/identity-verification/session`
   ```json
   { "nin": "12345678901", "consent": true }
   ```
   →
   ```json
   {
     "jobId": "coryd-0b7e…", "userId": "8f2c…", "token": "<short-lived>",
     "partnerId": "1234", "sandbox": true, "callbackUrl": null,
     "country": "NG", "idType": "NIN_V2"
   }
   ```
4. **Run Smile ID's SDK straight away** (the token is short-lived), with:
   - **Biometric KYC job:** `userId` and `jobId` from the response. Never make your own.
   - **Partner config:** `partnerConfig.partnerId = partnerId` and `partnerConfig.useSandbox = sandbox`. Set `callbackUrl` only if it isn't `null`.
   - **Token:** in the SDK's `network` config.
   - **ID details:** `{ country, idType, idNumber: <the NIN they entered> }`.

   Smile ID's SDK shows its own consent screen and liveness capture, and submits to Smile ID itself. The selfie never comes to our backend.
5. **On `onResult`**, poll `GET /profile/identity-verification/me` every 3 s:
   - Stop when `checkInProgress` turns `false`.
   - After about 2 minutes, show "We're checking your details" and refresh on screen open.
6. **If the SDK's token expires** (`onTokenExpired`), call `/session` again. That opens a fresh check; the old one is simply ignored.

### Status: `GET /profile/identity-verification/me`

```json
{
  "userId": "…", "status": "PENDING", "ninLast4": "8901", "submittedAt": "…", "decidedAt": null,
  "rejectionReason": null, "checkInProgress": false, "retryReason": "FACE_MISMATCH", "attemptsLeft": 3
}
```

| State | Show |
| --- | --- |
| `APPROVED` | Verified. Continue; booking (riders) and posting routes (drivers) are open. |
| `PENDING` + `checkInProgress: true` | Smile ID is still checking: a spinner or "Checking your details…". |
| `PENDING` + `retryReason` set | The last check failed in a way they can fix: show the message below and a **Try again** button (back to step 3). |
| `PENDING` with neither | Not done yet, or they left mid-check. Show the NIN step. **Onboarding isn't finished.** |
| `IN_REVIEW` | With our team: "We're checking your details". The rest of the app can be browsed; booking and posting stay closed. |
| `REJECTED` | Ops rejected it: show `rejectionReason` and a **Try again** button. |

| `retryReason` | Suggested message | Uses a try? |
| --- | --- | --- |
| `FACE_MISMATCH` | "Your selfie didn't match the photo on your NIN. Try again in good light, facing the camera." | Yes |
| `NIN_NOT_FOUND` | "We couldn't find that NIN. Check the number and try again." | Yes |
| `UNREADABLE` | "We couldn't use that selfie. Try again in good light, without a hat or glasses." | No |
| `TRY_LATER` | "We couldn't finish the check just now. Please try again in a few minutes." | No |

`attemptsLeft` counts down from 5. At 0, *"Too many attempts - contact CoRyd support…"* comes back from `/session`; show a contact-support screen. A check abandoned mid-way doesn't use a try, and stops showing as in progress after 15 minutes.

| Error | When |
| --- | --- |
| `400` *"NIN must be 11 digits"* | Bad NIN format |
| `400` *"Agree to the identity check to continue"* | `consent` not `true` |
| `400` *"Save your profile before verifying your identity"* | No profile yet for this role |
| `400` *"Add your first and last name, as on your NIN, before verifying"* | Name missing |
| `400` *"Your identity is already verified"* | Already approved |
| `400` *"Your identity check is with our team - we'll let you know once it's done"* | `IN_REVIEW` |
| `400` *"Too many attempts - contact CoRyd support to verify your identity"* | 5 tries used |
| `400` *"Identity checks are unavailable right now - try again later"* | Smile ID isn't set up on this environment |
| `502` *"Identity checks are unavailable right now - try again in a few minutes"* | Smile ID didn't issue a token |

The full NIN is never stored; the app gets back only `ninLast4`.

**The older submit** (`POST /profile/identity-verification` with `selfieUrl`) still works, so builds without Smile ID's SDK keep working, but those selfies are checked by ops by eye. It will be removed once everyone is on the new build; new code should use `/session`.

**Testing on dev:** dev uses Smile ID's sandbox (`sandbox: true`). Use Smile ID's sandbox [test identities](https://docs.usesmileid.com/developer-resources/essentials/testing-in-sandbox) — each gives a fixed result, so every state above can be seen.

### Onboarding status

`GET /identity/auth/onboarding-status` reports the identity check for **riders and drivers**:

```json
{
  "phoneVerified": true, "passwordSet": true, "profileComplete": true, "termsAccepted": true,
  "identityVerified": false,
  "identityVerificationStatus": "PENDING"
}
```

Treat onboarding as **incomplete while `identityVerificationStatus` is `PENDING`**, and gate booking/posting on `identityVerified`. To tell "checking" or "try again" apart from "not started", read `GET /profile/identity-verification/me`. `GET /profile/riders/me` also carries `identityVerificationStatus`.

### The gates

| Who | Blocked until approved | Response |
| --- | --- | --- |
| Rider | Booking a seat, accepting a reserved seat | `400` *"Verify your identity to book a ride"* |
| Driver | Posting a route (regular routes don't post their trips either) | `400` *"Verify your identity to post a route"* |

Send them to the identity step. Ride details (`GET /trip/routes/{id}`) already says *"Verify your identity to book a ride"* in `unavailableReason` before they tap Book.

### Driver side: "Verified rider" badge

Each passenger has `riderVerified: true|false`. Show a **"Verified rider"** badge when `true`.

---

## Driver's licence check (drivers)

The driver's licence (the `GOVERNMENT_ID` item in driver verification) is now checked by **Smile ID Document Verification**, using the same SDK as the identity check. The driver photographs their licence and takes a live selfie. Smile ID then:
- confirms the licence is genuine, checking its security features and that it isn't a photocopy or a photo of a screen;
- reads the name, licence number and expiry;
- matches the selfie to the photo on the licence.

The backend then checks that the name and number match the driver's profile and that the licence hasn't expired. If everything agrees, the licence is **approved automatically**, exactly as if ops had approved it: the driver gets the usual "item approved" notification and goes live if it was the last item. Anything doubtful goes to the ops verification queue. Only ops rejects.

This isn't an FRSC lookup (Smile ID can't do one in Nigeria). Vehicle, insurance and roadworthiness are unchanged: ops reviews them.

### Flow

1. **Profile first** (as today): `PUT /profile/drivers/me` with the name and licence number exactly as on the licence (`firstName`, `lastName`, `idNumber`, `dateOfBirth`). Leave out `governmentIdUrl`; the licence photo now goes to Smile ID, not to us.
2. **Open the check:** `POST /profile/drivers/me/licence-check/session` (no body) →
   ```json
   {
     "jobId": "coryd-dl-5c1a…", "userId": "8f2c…", "token": "<short-lived>",
     "partnerId": "1234", "sandbox": true, "callbackUrl": null,
     "country": "NG", "idType": "DRIVERS_LICENSE"
   }
   ```
3. **Run Smile ID's SDK straight away** as a **Document Verification** job:
   - `userId` and `jobId` from the response;
   - the same partner config and token as the identity check;
   - document `country` and `idType` from the response;
   - screens: document capture (front; back if the SDK asks) and a selfie with liveness.
4. **On `onResult`**, poll `GET /profile/drivers/me` every 3 s until `licenceCheckInProgress` is `false`. If it's still going after about 2 minutes, show "We're checking your licence".

### Status: `GET /profile/drivers/me`

New fields alongside the existing `governmentIdStatus` / `governmentIdRejectionReason`:

```json
{ "governmentIdStatus": "PENDING", "licenceCheckInProgress": false, "licenceRetryReason": "UNREADABLE", "licenceAttemptsLeft": 5 }
```

| State | Show |
| --- | --- |
| `APPROVED` | Licence verified. |
| `PENDING` + `licenceCheckInProgress: true` | "Checking your licence…" |
| `PENDING` + `licenceRetryReason` set | The message below and a **Try again** button (back to step 2) |
| `PENDING` with neither | Not done yet, or they left mid-check: show the licence step |
| `IN_REVIEW` | "Our team is checking your licence" |
| `REJECTED` | `governmentIdRejectionReason` and a **Try again** button (step 2 works after a rejection) |

| `licenceRetryReason` | Suggested message | Uses a try? |
| --- | --- | --- |
| `FACE_MISMATCH` | "Your selfie didn't match the photo on your licence. Try again in good light, facing the camera." | Yes |
| `NOT_A_LICENCE` | "That doesn't look like a Nigerian driver's licence. Photograph the front of your licence." | Yes |
| `UNREADABLE` | "We couldn't read your licence. Lay it flat in good light, with no glare, and fill the frame." | No |
| `TRY_LATER` | "We couldn't finish the check just now. Please try again in a few minutes." | No |

| Error | When |
| --- | --- |
| `400` *"Save your driver profile before verifying your licence"* | No driver profile |
| `400` *"Add your name and licence number, as on your licence, before verifying it"* | Name or licence number missing |
| `400` *"Your licence is already verified"* | Already approved |
| `400` *"Your licence is with our team - we'll let you know once it's checked"* | `IN_REVIEW` |
| `400` *"Too many attempts - contact CoRyd support to verify your licence"* | 5 tries used |
| `400` *"Licence checks are unavailable right now - try again later"* | Smile ID isn't set up on this environment |
| `502` *"Identity checks are unavailable right now - try again in a few minutes"* | Smile ID didn't issue a token |

**The older licence upload** (`governmentIdUrl` + `governmentIdPublicId` on `PUT /profile/drivers/me`) still works for builds without the SDK and sends the photo to ops. New code should use the session.

---

## Driver SOS and trip sharing

Drivers can raise an SOS and share their trip from the route itself, with or without bookings. Put both buttons on the driver's active-trip screen (and on an upcoming trip's screen).

| Call | Who | Notes |
| --- | --- | --- |
| `POST /trip/routes/{routePostId}/sos?latitude=&longitude=` | The route's driver | Allowed while the route is PUBLISHED, FULL or IN_PROGRESS. Returns the SOS incident. |
| `POST /trip/routes/{routePostId}/share` | The route's driver | Texts a live-tracking link to the driver's own emergency contacts; returns the link. |

Errors: `401` if not the route's driver; `400` *"This trip has already ended"*; share returns `400` *"Add an emergency contact to your profile before sharing a trip"* when they have none. Link to the emergency contacts screen.

The rider's SOS (`POST /trip/bookings/{bookingId}/sos?latitude=&longitude=`) is unchanged. The old `triggeredBy` query parameter is no longer read; the caller is taken from the token.

**What happens on any SOS, rider or driver:**
- The person's emergency contacts are texted.
- **CoRyd ops is texted and emailed**, with who pressed it, both phone numbers, the car's plate, the route and a map link.
- The person gets a confirmation push (`SOS_TRIGGERED`), as before.

The shared-trip page (`GET /trip/shared/{token}`) works for both kinds of link. For a driver's link, `status` is `null` and the location is the driver's own.

---

## "Are you OK?" safety checks

While a trip is IN_PROGRESS, the backend watches the driver's location. **Everyone on board** (driver and riders) gets an **"Are you OK?"** push when:

| Kind | Trigger | Push body |
| --- | --- | --- |
| `STOPPED` | Stopped 10+ min away from the start, the destination and every pickup/drop-off | "Your car has been stopped for a while away from any pickup. Tap to tell us you're fine, or get help." |
| `OFF_ROUTE` | 1 km+ off the route planned at departure for 5+ min | "Your car is a long way off the planned route. Tap to tell us you're fine, or get help." |
| `SIGNAL_LOST` | No location from the driver for 10 min | "We've lost the driver's location. Tap to tell us you're fine, or get help." |

Push data: `type = "SAFETY_CHECK"`, `referenceId = <alertId>`. Open a full-screen prompt with two big buttons:

`POST /trip/safety-checks/{alertId}/answer`

```json
{ "ok": true }
```
```json
{ "ok": false, "latitude": 6.52, "longitude": 3.60 }
```

- **"I'm fine"** → `ok: true`.
- **"I need help"** → `ok: false`, with the phone's current location. This raises an SOS for that person immediately, exactly like the SOS button.
- Response `data` is the check's status: `OPEN` (others haven't answered), `ALL_OK` (everyone said fine), `HELP`, or `ESCALATED`.
- If anyone hasn't answered within **3 minutes**, CoRyd ops is texted and emailed.
- `401` *"This safety check isn't for you"* if the user wasn't on that trip.

**Driver app, required:** keep posting location (`POST /trip/routes/{routePostId}/location` `{ "lat", "lng" }`) **for the whole trip, including in the background**. A 10-minute gap triggers the "lost signal" check for everyone on board.

---

## Blocking

A rider can block a driver, and a driver can block a rider (e.g. from trip history, the passenger list or ride details).

| Call | Notes |
| --- | --- |
| `POST /trip/blocks/{userId}` | Block. Safe to call twice. `400` *"You can't block yourself"*. |
| `DELETE /trip/blocks/{userId}` | Unblock. |
| `GET /trip/blocks` | The caller's own blocks: `[{ "id", "blockerId", "blockedId", "createdAt" }]`. |

Once either side blocks the other:
- the driver's rides disappear from that rider's search, nearby rides and notify-me pushes;
- booking returns `400` *"This ride isn't available to you"*;
- ride details shows the same `unavailableReason`.

Use neutral wording in the UI. The other person is never told they were blocked.

---

## Regular routes (drivers)

A driver sets a trip they make often once ("weekdays 07:00 Ikorodu → VI"), and CoRyd posts each day's trip for them, up to **3 days ahead** (the same limit as posting by hand). Each trip goes through the normal posting checks. A day that can't be posted (overlap, money owed, suspended) is skipped and retried an hour later.

| Call | Notes |
| --- | --- |
| `GET /trip/routes/templates` | The driver's regular routes. |
| `POST /trip/routes/templates` | Create; the next trips are posted straight away. |
| `PUT /trip/routes/templates/{id}` | Edit; applies to trips not yet posted. |
| `POST /trip/routes/templates/{id}/pause` · `/resume` | Stop / restart posting. |
| `DELETE /trip/routes/templates/{id}` | Remove; trips already posted stay until cancelled. |

Request (and response, plus `id` and `paused`):

```json
{
  "vehicleId": "…",
  "originLabel": "Ikorodu garage", "originLat": 6.62, "originLng": 3.51,
  "destinationLabel": "Eko Hotel Roundabout", "destinationLat": 6.43, "destinationLng": 3.43,
  "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
  "departureTime": "07:00",
  "seatCapacity": 3,
  "farePerSeat": 2500,
  "routeNote": "Pickup by the garage gate",
  "activeUntil": "2026-12-31",
  "standingSeats": [{ "riderId": "…", "seatCount": 1 }]
}
```

- `departureTime` is **Lagos time**, `HH:mm`.
- `activeUntil` is optional.
- `standingSeats` holds seats for favourite riders on every trip, using the existing reservation flow: the rider gets the usual push and 15 minutes to accept. They can't exceed `seatCapacity`.
- Errors (`400`): *"departureTime must be HH:mm, e.g. 07:00"*, *"The end date has already passed"*, *"Standing seats (n) can't exceed the seats offered (m)"*.

Posted trips are ordinary routes (`routeTemplateId` is set on them). Show them in "My routes" as usual, marked **"Regular"** if you like.

---

## Notify-me

Same endpoints as before (`POST/GET/DELETE /trip/routes/notify-me`). Changes:

- **No duplicates.** A request with both ends within 1 km of one of the rider's active requests, and the time within 60 minutes, returns `400` *"You're already waiting on this route around this time"*. A repeating request also covers a one-off on one of its days. **Disable the button after the first tap.**
- **One push per ride.** A rider with overlapping requests gets a single push per matching ride.
- **Along the way.** Riders are now also told about rides that pass them (same 1 km rule).
- **Driver demand list.** The driver's "Riders are looking for" list (`GET /trip/routes/demand`) and the 7 PM demand push now also count waiting riders along the driver's usual road, not only at its ends. No app change needed.

---

## Ride details: why a ride can't be booked

`GET /trip/routes/{routePostId}` already returns `bookable` and `unavailableReason`. New values, which match the errors booking would return:

- *"Verify your identity to book a ride"* (verification enforced)
- *"This account is suspended. Contact CoRyd support."*
- *"This ride isn't available to you"* (blocked)

Show the reason in place of the Book button.

---

## "How did you hear about us?" at signup

`POST /identity/auth/signup` takes an optional answer:

```json
{ "phone": "08012345678", "role": "RIDER", "heardAboutUs": "OTHER", "heardAboutUsOther": "My church bulletin" }
```

| `heardAboutUs` | Show as |
| --- | --- |
| `FRIEND_OR_FAMILY` | A friend or family member |
| `SOCIAL_MEDIA` | Social media (Instagram, X, TikTok, Facebook) |
| `WHATSAPP` | WhatsApp |
| `ESTATE_OR_WORKPLACE` | My estate or workplace |
| `FLYER_OR_POSTER` | A flyer or poster |
| `RADIO_OR_TV` | Radio or TV |
| `SEARCH_ENGINE` | Google or another search engine |
| `NEWS_OR_BLOG` | News or a blog |
| `OTHER` | Other: show a text box, sent as `heardAboutUsOther` (max 100 characters) |

- A single-choice list on the signup screen, skippable. Leave both fields out if the user skips it.
- `heardAboutUsOther` is only kept when the answer is `OTHER`.
- The first answer an account gives is kept; later signups (e.g. adding a role) don't overwrite it.

---

## Website: beta waitlist form

The "Join the CoRyd beta" page posts to a public endpoint. No login is needed.

`POST https://api-dev.coryd.ng/identity/beta-signups`

```json
{
  "name": "Ada Obi",
  "whatsappNumber": "0803 123 4567",
  "willMostly": "RIDE",
  "usualRoute": "Ikorodu to Lekki, weekday mornings",
  "device": "IPHONE"
}
```

| Field | Form control | Values |
| --- | --- | --- |
| `name` | Name | Up to 100 characters |
| `whatsappNumber` | WhatsApp number | A Nigerian mobile, any common format (`0803…`, `+234 803…`) |
| `willMostly` | I'll mostly: Ride / Drive | `RIDE` or `DRIVE` |
| `usualRoute` | Your usual route | Free text, up to 200 characters |
| `device` | My phone: iPhone / Android | `IPHONE` or `ANDROID` |

- **Success:** `200` with `message`: *"You're on the list - we'll send your invite on WhatsApp."* Show that message.
- **Submitting again with the same number** updates that person's entry and gets the same success message.
- **Errors (`400`):** *"Enter a valid Nigerian WhatsApp number, e.g. 0803 123 4567"*, a missing field (`"name: must not be blank"`), or an unknown choice (`"willMostly must be one of: RIDE, DRIVE"`).
- **CORS:** only the site's own addresses may call it (`BETA_FORM_ALLOWED_ORIGINS`, default `https://coryd.ng,https://www.coryd.ng`). If the page is hosted anywhere else, e.g. a preview URL, ask the backend team to add that address.

---

## Push notifications

Push data always carries `type`, plus `referenceId` and/or `routePostId` when relevant.

| `type` | New? | Sent to | Open |
| --- | --- | --- | --- |
| `SAFETY_CHECK` | **New** | Everyone on a trip | The "Are you OK?" prompt for `referenceId` (alertId) |
| `NOTIFY_ME_MATCHED` | Changed (also along-the-way rides, one per ride) | Waiting rider | Ride details for `routePostId` |
| `SOS_TRIGGERED` | Unchanged copy; now also for driver SOS | Whoever pressed SOS | Active trip |
| `BOOKING_CANCELLED` | New reason (suspension) | Rider | Trip history |

---

## Error reference (new in this update)

| Status | Message | Where |
| --- | --- | --- |
| 400 | Pick a pickup point on the driver's route | Book |
| 400 | `<field>` must be one of: … (e.g. *"heardAboutUs must be one of: FRIEND_OR_FAMILY, …"*) | Any request with an unknown value for a fixed list (was a 500 before) |
| 400 | Verify your identity to book a ride | Book, accept reservation |
| 400 | Verify your identity to post a route | Post a route |
| 400 | This ride isn't available to you | Book (blocked) |
| 400 | This account is suspended. Contact CoRyd support. | Book, post route |
| 401 | This account has been suspended. Contact CoRyd support. | Login, OTP, refresh, roles |
| 400 | You're already waiting on this route around this time | Notify-me |
| 400 | You can't block yourself | Block |
| 400 | This trip has already ended | Driver SOS / share |
| 400 | Add an emergency contact to your profile before sharing a trip | Driver share |
| 401 | This safety check isn't for you | Safety check answer |
| 400 | NIN must be 11 digits · Agree to the identity check to continue · Save your profile before verifying your identity · Add your first and last name, as on your NIN, before verifying · Your identity is already verified · Your identity check is with our team - we'll let you know once it's done · Too many attempts - contact CoRyd support to verify your identity · Identity checks are unavailable right now - try again later | Identity check (`/session`) |
| 502 | Identity checks are unavailable right now - try again in a few minutes | Identity check (`/session`), licence check (`/licence-check/session`) |
| 400 | Save your driver profile before verifying your licence · Add your name and licence number, as on your licence, before verifying it · Your licence is already verified · Your licence is with our team - we'll let you know once it's checked · Too many attempts - contact CoRyd support to verify your licence · Licence checks are unavailable right now - try again later | Licence check (`/drivers/me/licence-check/session`) |
| 400 | departureTime must be HH:mm, e.g. 07:00 · The end date has already passed · Standing seats (n) can't exceed the seats offered (m) | Regular routes |

---

## Backend switches that change app behaviour

| Setting | Default | Effect when changed |
| --- | --- | --- |
| `IDENTITY_VERIFICATION_ENFORCEMENT` | `STRICT` | Only NIN-verified riders can book and verified drivers can post. `OFF` only on environments where nobody can be verified yet. |
| `MATCHING_ALONG_ROUTE_ENABLED` | `true` | `false`: no along-the-way results and no pickup-on-road rule. |
| `MATCHING_TIME_TOLERANCE_MINUTES` / `MATCHING_GEO_TOLERANCE_METERS` | `60` / `2000` | Widened if searches come back empty too often during the pilot. |

---

## Integration checklist

**Rider app**
- [ ] Book with the search card's `pickup` (and `dropoff`); handle *"Pick a pickup point on the driver's route"*
- [ ] Search cards: "Passes near you" label, pickup/drop-off on the map, walking directions, "around HH:mm"
- [ ] Upgrade to Expo SDK 56+ and add Smile ID's v12 SDK (`@smileid/usesmileid`) - new native build
- [ ] Identity step in onboarding (required): name check, NIN + consent, `POST …/session`, Smile ID SDK with the returned job/token, poll `GET …/me`, status screens, `retryReason` messages + Try again, contact-support at 0 attempts left
- [ ] Existing users: send anyone with `identityVerified: false` to the identity step on app open
- [ ] Handle *"Verify your identity to book a ride"* → open the identity step
- [ ] "New" instead of stars when there are no ratings
- [ ] "Are you OK?" prompt from the `SAFETY_CHECK` push
- [ ] Block / unblock a driver; blocked list
- [ ] Notify-me: disable the button after the first tap; handle the duplicate 400
- [ ] Show `unavailableReason` on ride details
- [ ] Suspended: sign out on the 401; show the 400 message
- [ ] Optional "How did you hear about us?" on signup (`heardAboutUs`, text box for `OTHER`)

**Driver app**
- [ ] Identity step in onboarding (required; same Smile ID SDK flow, screens and endpoints as the rider app); handle *"Verify your identity to post a route"*
- [ ] Licence step: `POST /profile/drivers/me/licence-check/session`, Smile ID SDK Document Verification (licence + selfie), poll `GET /profile/drivers/me`, `licenceRetryReason` messages + Try again, contact-support at 0 tries left
- [ ] SOS and Share buttons on the trip screen (no booking needed)
- [ ] Background location for the whole trip
- [ ] "Are you OK?" prompt from the `SAFETY_CHECK` push
- [ ] Passenger list: "Verified rider" badge, drop-off points in route order
- [ ] Regular routes: list, create, edit, pause/resume, delete; standing seats for favourites
- [ ] Block / unblock a rider
- [ ] "New" instead of stars when there are no ratings
- [ ] Suspended: sign out on the 401; show the 400 message

---

## Appendix: Ops Console

Behind `/ops`, ops admins only.

| Call | Notes |
| --- | --- |
| `GET /ops/sos?page=&size=` | Every SOS, newest first: who pressed it (`triggeredByRole` RIDER/DRIVER, name, phone), the driver's name and phone, the trip, where, when. |
| `GET /ops/sos/safety-alerts?page=&size=` | "Are you OK?" checks: kind, status, and each person's answer (`FINE`, `HELP`, `WAITING`). |
| `POST /ops/users/{userId}/suspend` `{ "reason": "…" }` | Suspend (reason required). |
| `POST /ops/users/{userId}/reinstate` | Lift a suspension. |
| `GET /ops/identity-verification/queue?page=&size=` | Riders and drivers whose identity check waits on ops, oldest first: `firstName`, `lastName`, `ninLast4`, `attempts`, `checkOutcome` (`MATCH`, `NAME_MISMATCH`, `DETAILS_MISMATCH`, `NOT_FOUND`, `PROVIDER_ERROR`) and `checkSummary` (what Smile ID found, ending with the Smile ID job ID). **Renamed** from `premblyOutcome` / `premblySummary`. The selfie is in the Smile ID portal: search the job ID. `selfieUrl` is only set for the older selfie upload. A note *"Same NIN or face as another account"* names the other user. |
| `POST /ops/identity-verification/{userId}/decision` `{ "approve": true }` or `{ "approve": false, "reason": "…" }` | Approve or reject (reason required to reject; the user sees it). An uploaded selfie (older flow) is deleted either way. |
| `GET /ops/metrics/pilot?days=7` | Pilot health over the last `days` (1–90). See below. |
| `GET /ops/beta-signups?status=&willMostly=&device=&search=&page=&size=` | The website's beta waitlist, newest first: `name`, `whatsappNumber`, `willMostly`, `usualRoute`, `device`, `status` (`WAITING`/`INVITED`), `invitedAt`, `joinedAt` (set when they sign up in the app with that number). |
| `GET /ops/beta-signups/summary` | `total`, `riders`, `drivers`, `iphone`, `android`, `waiting`, `invited`, `joined`. |
| `POST /ops/beta-signups/{id}/invited` | Mark someone invited after sending their WhatsApp invite (TestFlight / Play link), so nobody is invited twice. |
| `GET /ops/metrics/heard-about-us?days=30` | Sign-ups in the last `days` by "How did you hear about us?": `{ from, to, signups, bySource: [{ source, signups }], otherAnswers: [{ source, signups }] }`. `NOT_ANSWERED` counts people who skipped it; `otherAnswers` are the most common "Other" texts. |

**Pilot metrics** (rates are 0–1, `null` when there was nothing to divide by):

- `from`, `to`: the window.
- `totals`:
  - `searches`, `searchesWithResults`, `searchSuccessRate`
  - `ridersSearching`, `ridersBooked`, `searchToBookingRate`
  - `routesPosted`, `seatsOffered`, `seatsBooked`, `fillRate`
- `weekTwoRepeatRate`: of riders who completed a trip the week before the window, the share who completed another in it.
- `notifyMeCreated` and `notifyMeBooked`: waits started in the window, and how many turned into a seat on a ride they were told about.
- `topSearchedRoutes[]`: the 10 most-searched routes by the place names riders picked, `{ originLabel, destinationLabel, searches, riders, searchesWithNoResults }`. The same place typed two ways counts twice.

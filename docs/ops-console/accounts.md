# Ops Console: Accounts

Account management for any rider or driver: suspend, reinstate, sign out everywhere, edit details, the account history, the full driver and rider profiles, and account deletion requests.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come with `success: false` and a `message` you can show as-is.
- **Who did it:** the admin is always taken from the token (id and name). The console never sends it.
- **Reasons:** every action that changes an account needs a `reason`, up to 500 characters. A missing or blank reason gets `400 reason: is required`.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Driver profile, with every section | `GET /drivers/{driverId}` |
| Rider profile, with every section | `GET /riders/{riderId}` |
| **Suspend** | `POST /users/{userId}/suspend` |
| **Reinstate** | `POST /users/{userId}/reinstate` |
| **Sign out everywhere** | `POST /users/{userId}/sign-out-everywhere` |
| **Edit details** (name, email) | `PATCH /users/{userId}/details` |
| **Edit vehicle** | `PATCH /drivers/{driverId}/vehicles/{vehicleId}` |
| **Give licence tries back** | `POST /drivers/{driverId}/verification/licence/reset-attempts` |
| **Account history** tab | `GET /users/{userId}/account-history` |
| **Deletion requests** list and detail | `GET /account-deletions` · `GET /account-deletions/{requestId}` |
| Delete account (unchanged) | `DELETE /users/{userId}` |

---

## 2. Suspend, reinstate, sign out everywhere

All three take the same body and return no data.

```json
{ "reason": "Threatened a rider" }
```

### `POST /users/{userId}/suspend`

**What happens:**
- The account is suspended. Every session on every device ends **within seconds, on every service**. It doesn't wait for the token to expire.
- The person can't sign in, refresh a session, post or book.
- **Upcoming trips are cancelled:**
  - A suspended driver's published and full routes are cancelled. Every rider is told and refunded in full.
  - A suspended rider's seats are cancelled with a full refund, including a pickup already under way (driver on the way or waiting). The driver is told "A seat opened up". They're never told why.
  - Unpaid seat holds are released.
  - **A trip already in progress is never interrupted.** Ops handles that one directly.
- It's recorded in the account history with the reason. The profile's `suspension` shows it.

**Errors:**

| Status | `message` |
|---|---|
| `400` | `reason: is required` |
| `400` | `This account is already suspended` |
| `404` | `User not found` |

### `POST /users/{userId}/reinstate`

**The reason is now required.** Before, this call took no body.

**What happens:**
- The person can sign in again, and post or book.
- **Sessions ended by the suspension stay ended.** The person signs in again; old tokens aren't revived.
- Trips cancelled by the suspension stay cancelled.
- The suspension and the reinstate stay in the history (`suspensionHistory` on the profile).

**Errors:**

| Status | `message` |
|---|---|
| `400` | `reason: is required` |
| `400` | `This account isn't suspended` |
| `404` | `User not found` |

### `POST /users/{userId}/sign-out-everywhere`

Use it for a lost or stolen phone, or a compromised account.

**What happens:**
- Every session on every device ends within seconds. The person can sign straight back in.
- Every device on the account is marked signed out (`devices[].signedIn: false`).
- The person gets a push: **"You've been signed out"**, "For your security, CoRyd signed you out on all your devices. Sign in again to carry on."
- Recorded in the account history.

**Errors:** `400 reason: is required`, `404 User not found`.

**After any of the three,** refetch the profile and the directory counts.

---

## 3. Profiles: `GET /drivers/{driverId}` and `GET /riders/{riderId}`

One call each returns the whole profile.

**Where each part comes from:**
- The directory row's fields, unchanged.
- **identity-service:** account status, roles, devices, suspension history, recent history.
- **profile-service:** email, verification, vehicles, emergency contacts.

**If a service is unreachable,** its parts come back `null` and the rest still loads. Show a dash or "Couldn't load" for those parts.

### Driver

```json
{
  "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
  "status": "SUSPENDED",
  "verificationStatus": "APPROVED",
  "suspension": {
    "suspendedAt": "2026-10-01T10:00:00Z",
    "reason": "Threatened a rider",
    "suspendedBy": { "id": "0b5e…", "name": "Ada Ops" }
  },
  "vehicle": { "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA" },
  "performance": { "totalTrips": 415, "rating": 4.88, "ratingCount": 312, "totalEarnings": 803200.00, "grossEarnings": 845473.68,
                   "reliabilityPercent": 97, "reportsFiled": 0 },
  "account": {
    "ref": "D-4F2A91",
    "joinedAt": "2026-01-12T09:00:00Z",
    "status": "SUSPENDED",
    "roles": ["DRIVER", "RIDER"],
    "phoneVerifiedAt": "2026-01-12T09:01:00Z",
    "lastSeenAt": "2026-10-01T08:12:00Z",
    "sessionsValidAfter": "2026-10-01T10:00:00Z",
    "devices": [
      { "deviceId": "a1b2…", "deviceName": "Emeka's Pixel", "platform": "ANDROID",
        "lastSeenAt": "2026-10-01T08:12:00Z", "signedIn": false, "biometricEnabled": false }
    ]
  },
  "email": "emeka@mail.ng",
  "verification": {
    "status": "APPROVED",
    "verifiedSince": "2026-01-20T12:00:00Z",
    "items": [
      { "item": "GOVERNMENT_ID", "label": "Driver's licence", "status": "APPROVED", "awaitingProvider": false,
        "submittedAt": "…", "resubmitted": false },
      { "item": "VEHICLE", "label": "Vehicle registration", "status": "APPROVED", "awaitingProvider": false, "submittedAt": "…", "resubmitted": false },
      { "item": "INSURANCE", "label": "Insurance certificate", "status": "APPROVED", "awaitingProvider": false, "submittedAt": "…", "resubmitted": false },
      { "item": "ROADWORTHINESS_CERT", "label": "Roadworthiness certificate", "status": "APPROVED", "awaitingProvider": false, "submittedAt": "…", "resubmitted": false }
    ],
    "licenceAttempts": { "used": 1, "max": 5, "left": 4, "lockedOut": false, "canReset": false,
                         "resetBlockedReason": "This driver's licence is already verified - there are no tries to give back" },
    "identity": { "status": "APPROVED", "submittedAt": "…", "decidedAt": "…", "rejectionReason": null }
  },
  "vehicles": [
    { "id": "9c1d…", "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA",
      "seatCapacity": 4, "hasAC": true, "status": "APPROVED", "rejectionReason": null, "primary": true }
  ],
  "emergencyContacts": [ { "id": "c-1", "name": "Ngozi Okafor", "phone": "08011112222", "primary": true } ],
  "suspensionHistory": [
    { "suspendedAt": "2026-03-01T10:00:00Z", "suspendedBy": { "id": "0b5e…", "name": "Ada Ops" }, "reason": "Late twice",
      "reinstatedAt": "2026-03-02T09:00:00Z", "reinstatedBy": { "id": "77aa…", "name": "Bola Ops" }, "reinstateReason": "Apologised" },
    { "suspendedAt": "2026-10-01T10:00:00Z", "suspendedBy": { "id": "0b5e…", "name": "Ada Ops" }, "reason": "Threatened a rider",
      "reinstatedAt": null, "reinstatedBy": null, "reinstateReason": null }
  ],
  "recentActions": [ { "id": "…", "action": "SUSPENDED", "actor": { "id": "0b5e…", "name": "Ada Ops" }, "reason": "Threatened a rider",
                       "changes": [ { "field": "status", "before": "ACTIVE", "after": "SUSPENDED" } ], "at": "2026-10-01T10:00:00Z" } ],
  "pendingDeletion": null
}
```

| Field | Meaning |
|---|---|
| `suspension.suspendedBy` | The admin who suspended. `null` for suspensions made before this was recorded. |
| `account.status` | `ACTIVE` or `SUSPENDED` |
| `account.roles` | Every role the account holds. A driver who also rides has both. |
| `account.lastSeenAt` | The last sign-in or session refresh on any device. The app refreshes its session at least daily while in use. |
| `account.sessionsValidAfter` | Sessions started before this were ended by ops. `null` if that never happened. |
| `account.devices[]` | Every phone the account signed in from, most recent first. `signedIn: false` once signed out, by the person or by ops. |
| `verification.items` | Same statuses as the verification screens, in the order licence, vehicle, insurance, roadworthiness |
| `verification.licenceAttempts` | The licence check's tries. `lockedOut`: all 5 used. `canReset` / `resetBlockedReason`: see section 6. |
| `verification.identity` | The identity (NIN) check, or `null` if never started |
| `vehicles[]` | Every vehicle the driver registered. `primary` is the one the verification screens review. |
| `suspensionHistory` | Every suspension, oldest first. Each pairs with the reinstate that ended it; `reinstated*` are `null` while it's still in force. |
| `recentActions` | The 10 latest history entries; see section 4 for the rest |
| `pendingDeletion` | A deletion that was asked for and refused: `{ "requestId", "lastAttemptAt", "blockers": [ … ] }`. Otherwise `null`. |

### Rider

The same `rider`, `status`, `suspension` and `activity` fields as before, plus:

```json
{
  "rider": { "…": "…" }, "status": "ACTIVE", "suspension": null,
  "account": { "ref": "R-4F2A91", "joinedAt": "…", "status": "ACTIVE", "roles": ["RIDER"], "phoneVerifiedAt": "…",
               "lastSeenAt": "…", "sessionsValidAfter": null, "devices": [ … ] },
  "activity": { "…": "…" },
  "email": "ada@mail.ng",
  "verification": { "identity": { "status": "IN_REVIEW", "submittedAt": "…", "decidedAt": null, "rejectionReason": null } },
  "emergencyContacts": [ … ],
  "suspensionHistory": [ … ],
  "recentActions": [ … ],
  "pendingDeletion": null
}
```

**Errors:** an unknown driver or rider still gets `404 Driver not found` / `404 Rider not found`.

---

## 4. Account history: `GET /users/{userId}/account-history`

| Param | Default | Values |
|---|---|---|
| `page` | `0` | 0-based |
| `size` | `20` | max `100` |

Newest first. Every ops action on the account is listed: who, when, why, and what changed. Entries are never edited, and suspensions stay after a reinstate.

```json
{
  "content": [
    { "id": "…", "action": "DETAILS_EDITED", "actor": { "id": "0b5e…", "name": "Ada Ops" },
      "reason": "Typo - checked against the licence",
      "changes": [ { "field": "lastName", "before": "Okafr", "after": "Okafor" } ],
      "at": "2026-11-02T09:00:00Z" }
  ],
  "page": 0, "size": 20, "totalElements": 4, "totalPages": 1, "last": true
}
```

| `action` | What it was | `changes` |
|---|---|---|
| `SUSPENDED` | Suspended | `status` ACTIVE → SUSPENDED |
| `REINSTATED` | Reinstated | `status` SUSPENDED → ACTIVE, and the `suspensionReason` it cleared |
| `SIGNED_OUT_EVERYWHERE` | Signed out on every device | none |
| `DETAILS_EDITED` | Name or email changed | each field changed |
| `VEHICLE_EDITED` | Vehicle changed | each field changed |
| `LICENCE_ATTEMPTS_RESET` | Licence tries given back | none; `reason` is the note, if any |
| `IDENTITY_ATTEMPTS_RESET` | Identity (NIN) tries given back, from the identity screen | none; `reason` is the note, if any |

**Notes:**
- `actor` is `null` for a suspension made before the history existed.
- The account history is deleted with the account.
- Verification decisions are in their own log (`GET /drivers/{driverId}/verification/history`), not here.

**Errors:** `404 User not found`.

---

## 5. Editing details

### Name and email: `PATCH /users/{userId}/details`

Send only the fields that change, plus the reason.

```json
{ "lastName": "Okafor", "email": "emeka@mail.ng", "reason": "Typo - checked against the licence" }
```

| Field | Rules |
|---|---|
| `firstName`, `lastName` | Up to 100 characters. They can't be blank. |
| `email` | A valid address, up to 254 characters. It's stored in lower case. |
| `reason` | Required |

**Response:**

```json
{ "changes": [ { "field": "lastName", "before": "Okafr", "after": "Okafor" } ],
  "vehicle": null, "sentBackToReview": false, "historyRecorded": true }
```

**What changes:**
- The person's driver and rider profiles both change; they're the same person.
- So does the name on their account. It shows in the app and on their next sign-in or refresh.
- A name confirmed by an identity (NIN) check can still be corrected here. The person can't change it themselves.
- No document goes back to review.

**Phone numbers can't be edited by ops.** Sending `phone` or `phoneNumber` gets a `400`:
`Phone numbers can't be changed from the ops console - the phone number is how the person signs in, so only they can change it`.

**Errors:**

| Status | `message` |
|---|---|
| `400` | `reason: is required` · `email: must be a well-formed email address` · the phone message above |
| `400` | `Send the first name, last name or email to change` |
| `400` | `The first name can't be blank` (likewise last name and email) |
| `400` | `Nothing to change - these are already the details on the account` |
| `404` | `No rider or driver profile for this user` |

`historyRecorded: false` is rare. It means the change was saved but couldn't be added to the history; it's logged for engineering. **Don't retry**, because the edit already stands.

### Vehicle: `PATCH /drivers/{driverId}/vehicles/{vehicleId}`

Send only the fields that change, plus the reason. The `vehicleId` comes from the profile's `vehicles[].id`.

```json
{ "plateNumber": "KJA-100-AA", "reason": "Driver re-registered the car" }
```

| Field | Rules |
|---|---|
| `make`, `model` | Up to 50 characters |
| `year` | 1980–2100 |
| `color` | Up to 30 characters |
| `plateNumber` | Up to 20 characters. It's stored in upper case, and must not be on another vehicle (any case). |
| `seatCapacity` | 1–14 |
| `reason` | Required |

**Response:**

```json
{ "changes": [ { "field": "plateNumber", "before": "LND-441-AA", "after": "KJA-100-AA" } ],
  "vehicle": { "id": "9c1d…", "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "KJA-100-AA",
               "seatCapacity": 4, "hasAC": true, "status": "IN_REVIEW", "rejectionReason": null, "primary": true },
  "sentBackToReview": true, "historyRecorded": true }
```

**Changing what identifies the car sends an approved vehicle back to review.** That's the plate, make, model or year. The approved registration documents were for the car as it was.

**Then:**
- The vehicle registration goes to `IN_REVIEW` and appears in the Verification Queue. Approve it there as usual.
- If it's the driver's primary vehicle and they were verified, **they can't post trips** until it's approved again. Trips already posted aren't touched.
- The driver gets a push and email: "Vehicle registration being re-checked".
- The verification audit log records `SENT_BACK_TO_REVIEW`, with what changed.

**Changing only the colour or seat count** never needs a review. Neither does editing a vehicle that isn't approved yet; its status stays as it is.

**Errors:**

| Status | `message` |
|---|---|
| `400` | `reason: is required`, or a field outside the limits above |
| `400` | `Send the vehicle details to change` |
| `400` | `Nothing to change - these are already the vehicle's details` |
| `400` | `A vehicle with this plate number is already registered` |
| `404` | `Vehicle not found for this driver` |

---

## 6. Licence tries: `POST /drivers/{driverId}/verification/licence/reset-attempts`

A driver who used all 5 licence-check tries is told in the app to contact support. This gives the tries back. The rules are the same as giving back identity tries ([rider-identity.md](rider-identity.md)).

**Body (optional):**

```json
{ "note": "Called the driver - their camera was broken" }
```

**Response:** the licence tries now.

```json
{ "used": 0, "max": 5, "left": 5, "lockedOut": false, "canReset": false,
  "resetBlockedReason": "No tries have been used - there's nothing to reset" }
```

**What happens:**
- The licence's status doesn't change.
- The driver gets a push: **"You can verify your driver's licence again"**.
- It's logged as `ATTEMPTS_RESET` in the verification audit log, and as `LICENCE_ATTEMPTS_RESET` in the account history, with the note.

**When to show the button:** when `verification.licenceAttempts.canReset` is true on the driver profile. Otherwise show `resetBlockedReason`.

**Errors:**

| Status | `message` |
|---|---|
| `400` | `This driver's licence is already verified - there are no tries to give back` |
| `400` | `No tries have been used - there's nothing to reset` |
| `404` | `Driver profile not found` |

---

## 7. Account deletion requests (read-only)

People delete their own account in the app. Ops can delete one with `DELETE /users/{userId}`.

**A deletion is refused while the person is on a trip or has money still moving.** These calls list those attempts.

| `status` | Meaning |
|---|---|
| `PENDING` | The last try was refused. `blockers` says why. It stays here until a later try succeeds. |
| `COMPLETED` | The account is deleted |

### `GET /account-deletions`

| Param | Default | Values |
|---|---|---|
| `status` | none = both | `PENDING` · `COMPLETED` (anything else gets a `400`) |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

The most recent try comes first.

```json
{
  "content": [
    { "id": "d71c…", "userId": "4f2a91c0-…", "status": "PENDING", "roles": ["RIDER"], "phoneLast4": "6321",
      "firstName": "Emeka", "lastName": "Okafor", "phone": "08053636321", "joinedAt": "2026-01-12T09:00:00Z",
      "requestedBy": "SELF", "requestedAt": "2026-11-01T08:00:00Z", "attempts": 2, "lastAttemptAt": "2026-11-02T08:00:00Z",
      "blockers": ["you're on a trip right now - wait until it's finished"], "completedAt": null, "completedBy": null }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true
}
```

| Field | Meaning |
|---|---|
| `requestedBy`, `completedBy` | `SELF` (the person, in the app) or the ops admin's user id |
| `firstName`, `lastName`, `phone` | Shown only while the account still exists (`PENDING`) |
| `roles`, `phoneLast4`, `joinedAt` | Kept after deletion, so a completed request can still be recognised. Nothing else personal is kept. |
| `attempts` | How many tries there were, including the one that succeeded |

### `GET /account-deletions/{requestId}`

Returns one request, in the same shape. An unknown id gets `404 Account deletion request not found`.

---

## 8. How "ends every session within seconds" works

Access tokens last 24 hours. Revoking a refresh token alone would let an issued access token keep working on other services until it expired. Instead:

1. **identity-service** keeps a per-user cut-off, `tokens_valid_after`.
   - It's set when ops suspends the account, signs it out everywhere, or deletes it.
   - It only ever moves forward. A reinstate never revives old tokens.
   - The same call revokes every refresh token and signs out every device.
2. Once the change commits, identity publishes the user's state `{ userId, tokensValidAfter, suspended, reason, stateAt }`.
   - It goes on **`coryd-user-tokens-revoked`**, a compacted topic keyed by user id and kept forever. Only the latest state per user is retained.
   - identity applies the change to its own copy straight away.
3. **Every service's `JwtFilter`** (in `common`) keeps an in-memory map of those states and checks each request against it. It's one map lookup, with no I/O.
   - A token issued before the user's cut-off is refused with **`401 Your session has ended. Please sign in again.`**
   - Every token of a suspended account is refused with **`401 This account has been suspended. Contact CoRyd support.`**
   - Tokens carry their issue time to the millisecond (`iat_ms`), so a sign-in a moment after the cut-off is accepted. Older tokens without `iat_ms` use `iat`.
4. **The map is fed two ways, so a lagging Kafka never leaves a gap:**
   - **Live:** each instance of each service reads the topic from the beginning at startup and then follows it. It assigns itself every partition, with no consumer group, so every instance sees every record. Changes arrive within about a second.
   - **Snapshot:** at startup, each service loads every revoked or suspended user from identity: `GET /identity/internal/users/token-revocations`, with the internal key. It retries every 30 seconds until that works, then re-reads every 5 minutes (`coryd.token-revocation.refresh-minutes`).
   - The two merge in any order: the cut-off is the latest seen, and "suspended" follows the newer state.
5. **Users the map has never heard of are let through.** That's everyone who was never revoked. If the snapshot is unreachable and Kafka is down at startup, a service fails open until either answers; it logs a warning.

**Also covered:**
- trip-service's WebSocket sign-in (`JwtUtil.isTokenValid`).
- Public endpoints (sign-in, refresh) still work for a request carrying a dead token. The refusal applies only where authentication is needed.

**Why not a check against identity on every request?** It would add a network call to every request on every service, and make identity a single point of failure. **Why not shorter-lived access tokens?** They'd still leave a window of minutes, and would mean far more refresh traffic from the apps.

**Configuration (all optional):**

| Property | Default | Meaning |
|---|---|---|
| `coryd.token-revocation.kafka.enabled` | `true` | Follow the topic |
| `coryd.token-revocation.bootstrap.enabled` | `true` | Load the snapshot at startup |
| `coryd.token-revocation.refresh-minutes` | `5` | How often the snapshot is re-read |
| `coryd.token-revocation.identity-url` | `services.identity.url`, else `IDENTITY_SERVICE_URL` | Where the snapshot comes from |

identity-service reads its own database for the snapshot, so it doesn't call itself.

**Migrations:** `coryd:080` (`identity.users.tokens_valid_after`; accounts already suspended are cut off from their `suspended_at`), `coryd:081` (`identity.account_actions`; already-suspended accounts get a `SUSPENDED` entry with no actor), `coryd:082` (`identity.account_deletion_requests`).

---

## 9. What the mobile app sees

| Action | On the person's phone |
|---|---|
| **Suspend** | Within seconds, every call returns `401` with `This account has been suspended. Contact CoRyd support.` Refreshing the session or signing in returns the same message. Upcoming bookings get "Booking cancelled… Your payment has been refunded." pushes; a driver gets "Route cancelled" pushes. A trip under way carries on. |
| **Reinstate** | Nothing is pushed. The old session is still dead: the next call returns `401 Your session has ended. Please sign in again.` Signing in works again. |
| **Sign out everywhere** | Push: "You've been signed out". Within seconds, every call on every device returns `401 Your session has ended. Please sign in again.` Refreshing returns `400 This session is no longer valid - please sign in again`. Signing in works straight away. |
| **Edit name / email** | The new details show on their profile screen. The name in their session updates on the next refresh or sign-in. No push. |
| **Edit vehicle: plate, make, model or year** (approved car) | Push and email: "Vehicle registration being re-checked". Posting is blocked until ops approves it. Already-posted trips are untouched. |
| **Edit vehicle: colour or seats** | Shown on their vehicle; no push |
| **Licence tries given back** | Push: "You can verify your driver's licence again". The licence check opens again in the app. |
| **Deleted** | Within seconds, `401 Your session has ended. Please sign in again.`; signing in fails, since the account is gone |
| **A seat cancelled because the rider was suspended** (the driver's phone) | Push: "A seat opened up". The rider's suspension isn't mentioned. |

**For the app team:** treat any `401` with one of these two messages as signed out. Clear the session and show the message on the sign-in screen.

---

## 10. Changes from before

- `POST /users/{userId}/reinstate` now **needs a body** with a `reason`. An empty call gets `400`.
- Suspending an account that's already suspended gets `400`, as does reinstating one that isn't.
- `account` on both profiles has more fields, `suspension` has `suspendedBy`, and there are new top-level sections. Every existing field is unchanged.

---

## 11. Checklist

- [ ] Reason dialog for **Suspend**, **Reinstate** and **Sign out everywhere**; refetch the profile and counts afterwards.
- [ ] Profile sections from the one call: account and devices, verification, vehicles, emergency contacts, suspension history, recent actions, pending deletion.
- [ ] **Account history** tab from `/users/{userId}/account-history`, paged.
- [ ] **Edit details**: name and email only, with a reason. The phone field is read-only.
- [ ] **Edit vehicle**: warn before changing the plate, make, model or year of an approved car: "This sends the vehicle back to review and stops the driver posting until it's approved."
- [ ] **Give licence tries back**: show it when `licenceAttempts.canReset`; otherwise show `resetBlockedReason`.
- [ ] **Deletion requests** list with `PENDING` / `COMPLETED` tabs, and a detail view.

# Ops console changes: what the rider and driver apps must handle

CoRyd now has an ops console (admin.coryd.ng) where the support, verification and finance teams act on riders, drivers, trips and money. Many of those actions reach the apps as a push, an entry in the notification bell, a changed field or a new error.

This guide lists every change the apps must handle or might notice. It covers the exact payloads, endpoints and error messages, which screen to open, and what to test. It leaves out how the console works inside, except where that explains what the app sees.

Everything here was checked against the backend code (notification-service, identity-service, profile-service, trip-service, payment-service). Where the ops-console docs say something different, this guide follows the code. Those differences are listed in [section 17](#17-where-the-ops-console-docs-differ-from-the-code).

**Basics**
- **API base (dev):** `https://api-dev.coryd.ng`. The paths below include the service prefix (`/identity`, `/profile`, `/trip`, `/payment`, `/notification`).
- **Envelope:** `{ responseId, timestamp, success, message, data }`, as before. Errors have `success: false` and a `message` you can show as-is.
- **Times** are ISO-8601 UTC. Show them in Lagos time (`Africa/Lagos`, UTC+1, no daylight saving).
- **Nothing in this guide removes or renames a field.** Every change adds a push type, a field or an error message.

---

## Contents

1. [What changed for the apps, at a glance](#1-what-changed-for-the-apps-at-a-glance)
2. [Push and bell basics](#2-push-and-bell-basics)
3. [Onboarding reminders](#3-onboarding-reminders)
4. [Identity (NIN + selfie) decisions by ops](#4-identity-nin--selfie-decisions-by-ops)
5. [Driver documents](#5-driver-documents)
6. [Account actions: suspension, sign-out everywhere, deletion, edits](#6-account-actions-suspension-sign-out-everywhere-deletion-edits)
7. [Trips and bookings cancelled or resolved by ops](#7-trips-and-bookings-cancelled-or-resolved-by-ops)
8. [Safety and support](#8-safety-and-support)
9. [Money](#9-money)
10. [Broadcasts](#10-broadcasts)
11. [Settings ops can now change without an app release](#11-settings-ops-can-now-change-without-an-app-release)
12. [Privacy and security](#12-privacy-and-security)
13. [WhatsApp and SMS](#13-whatsapp-and-sms)
14. [What the apps can ignore](#14-what-the-apps-can-ignore)
15. [How to test on dev](#15-how-to-test-on-dev)
16. [Checklist for the apps](#16-checklist-for-the-apps)
17. [Where the ops-console docs differ from the code](#17-where-the-ops-console-docs-differ-from-the-code)

---

## 1. What changed for the apps, at a glance

Priority: **Must** means the app is wrong or confusing without the change. **Should** means the change is clearly better UX. **Nice** means it can wait.

| Area | What | App action | Priority |
|---|---|---|---|
| Sessions | Suspension, sign-out everywhere and deletion end every session within seconds. Any authenticated call then returns `401` with `This account has been suspended. Contact CoRyd support.` or `Your session has ended. Please sign in again.` | Treat both as signed out: clear the session (and any biometric-stored refresh token) without trying a refresh, then show the message on the sign-in or support screen | **Must** |
| Sessions | New push `SIGNED_OUT_EVERYWHERE` ("You've been signed out") | Don't deep-link anywhere. Show the sign-in screen | Must |
| Sessions | After a suspension, sign-out everywhere or password reset, the backend drops the phone's push token itself (the app can't, its session is dead) | Register the FCM token again after every sign-in (the app already does) | Should |
| Onboarding | New daily push `ONBOARDING_REMINDER`, with `step`, `reason` and `role` in the data and `step` and `role` on the bell entry | Route each step to its screen. `NIN_LOCKED_OUT` goes to support | **Must** |
| Identity | New pushes `IDENTITY_VERIFICATION_APPROVED`, `_REJECTED` and `_RETRY` when ops decides a NIN check | Refresh the identity status. Show `rejectionReason` and a Try again button | **Must** |
| Driver documents | `VERIFICATION_ITEM_REJECTED` is reused for "Please upload your X again". There's a new `resubmissionRequests[]` on verification-status | Show the reason and a re-upload button for each document. Expect posting to be blocked | **Must** |
| Driver documents | `DOCUMENT_EXPIRING` now carries `documentType` (and `vehicleId`) in the push data, and `documentType` on the bell entry | Open Verification Status on that document | Should |
| Driver documents | New pushes `VERIFICATION_ITEM_IN_REVIEW` (vehicle being re-checked after an ops edit) and `VERIFICATION_ITEM_RETRY` (licence tries given back) | Open vehicle status or the licence check | Should |
| Trips | Ops can cancel a trip or a seat. The existing `BOOKING_CANCELLED` and `ROUTE_CANCELLED` pushes now say "CoRyd support cancelled…" | No new type. Make sure an active or live booking screen handles cancellation at any stage, including a rider already aboard | Should |
| Trips | New `cancelledBy` (`RIDER`, `DRIVER` or `CORYD`) on `GET /trip/bookings/{bookingId}`. The status alone can't say who cancelled | Word a cancelled booking from `cancelledBy`, not from the status: "Cancelled by CoRyd" for `CORYD` | **Must** |
| Support | New push `ISSUE_REPORT_UPDATED` when ops resolves or dismisses a report with a message | Show the message. Open the trip when `referenceId` is set, otherwise the bell | Should |
| Money | Ops refunds reuse `REFUND_ISSUED` and `DRIVER_OWED_CHANGED`. A debt adjustment adds an `OPS_ADJUSTMENT` entry to the driver's owed history with no push | Render `OPS_ADJUSTMENT`, with a signed amount and a note | Should |
| Broadcasts | New push `BROADCAST`, with `referenceId` set to the broadcast id. The bell entry has `eventType: BROADCAST` | Open the bell. Make sure the announcements switch writes `promotionalOptIn` | **Must** (handle the type) |
| Privacy | `GET /profile/drivers/{id}/documents` and `GET /payment/payments/bookings/{id}` now return `403` for other people | Nothing if the app only reads its own. Don't treat these 403s as sign-out | Nice |
| Privacy | The bell, preferences and device-token endpoints (`/notification/notifications/…`, `/notification/preferences/users/{userId}`, `/notification/device-tokens/users/{userId}`) now act only for the signed-in user: another user's id gets `403 You can only manage your own notifications`, another user's notification id gets `404`. New `GET /notification/preferences/users/{userId}` reads the switches | Always send the signed-in user's own id (from the token you're using, not a cached one). Read the switches with the new GET | Should |
| Settings | Ops can change the booking window, cancellation fee and free-cancellation window, document-reminder days and commission at runtime | Don't hard-code 10 minutes, 25%, 2 hours, 14 days or 5%. Read `bookableUntil`, `holdExpiresAt`, the commission-rate endpoint and the new cancellation quote (`GET /trip/bookings/{bookingId}/cancellation-quote`) | Should |
| WhatsApp | A WhatsApp OTP resend falls back to SMS while WhatsApp is off. `whatsappOptIn` exists as a preference | Optional "WhatsApp messages" switch | Nice |

---

## 2. Push and bell basics

Every push and bell entry follows the same rules. Most pushes in this guide are not new types, so it helps to know the shape.

### Push data (FCM `data`)

```json
{ "type": "IDENTITY_VERIFICATION_REJECTED" }
{ "type": "BOOKING_CANCELLED", "referenceId": "<bookingId>" }
{ "type": "ROUTE_CANCELLED", "routePostId": "<routePostId>" }
{ "type": "ONBOARDING_REMINDER", "step": "INSURANCE", "reason": "RESUBMIT", "role": "DRIVER" }
{ "type": "BROADCAST", "referenceId": "<broadcastId>" }
{ "type": "DOCUMENT_EXPIRING", "documentType": "INSURANCE", "vehicleId": "<vehicleId>" }
```

- `type` is always present. Route the tap on it.
- `referenceId` is **left out** when there is nothing to reference. It isn't sent as `null`; FCM data can't carry `null`, and the backend omits the key. Usually it's a booking id. For `BROADCAST` it's the broadcast id.
- `routePostId` is set on driver route pushes such as `ROUTE_CANCELLED`.
- The title and body are in the `notification` block. Show them as sent.
- **Unknown `type`:** open the notifications screen. New types will be added.

### Bell entry: `GET /notification/notifications/{userId}`

Newest first. Unread count: `GET /notification/notifications/{userId}/unread-count`. Mark read: `POST /notification/notifications/{notificationId}/read`. Mark all read: `POST /notification/notifications/users/{userId}/read-all`.

`{userId}` must be the signed-in user's own id, and a notification can only be marked read by its owner. See [section 12](#12-privacy-and-security).

```json
{
  "id": "…", "createdAt": "2026-11-16T07:00:03Z", "userId": "…",
  "title": "You're almost there, Ada", "body": "Upload your insurance certificate to start driving.",
  "eventType": "ONBOARDING_REMINDER",
  "bookingId": null, "routePostId": null,
  "step": "INSURANCE", "role": "DRIVER",
  "read": false, "readAt": null
}
```

- `eventType` is the push `type`.
- `bookingId` is what the push sends as `referenceId` for booking pushes. It's `null` for `BROADCAST`; the broadcast id is only in the push.
- **New fields:** `step` and `role`. They're set only on `ONBOARDING_REMINDER` and are `null` on everything else.
- **New field:** `documentType` (`INSURANCE` or `ROADWORTHINESS_CERT`). It's set only on `DOCUMENT_EXPIRING` and is `null` on everything else, including `DOCUMENT_EXPIRING` entries written before this change.
- One entry per notification, however many devices the person has. **The entry is written even when the person has no device token**, so the bell is where someone without push permission sees everything.

---

## 3. Onboarding reminders

**What triggers it:** a daily job in notification-service. It runs at **08:00 Lagos** by default; ops can change the time in Platform Settings. It reminds riders and drivers who haven't finished signing up **and** are waiting on themselves, not on ops.

**Who gets one:** accounts that can sign in: phone verified, password set, active, not suspended, no deletion request pending.

**Who never gets one:**
- people who have finished (rider: profile, terms and NIN approved; driver: approved at least once and terms accepted);
- people whose only remaining item is with ops or with Smile ID;
- suspended accounts, and accounts waiting to be deleted.

### Push

| Field | Values |
|---|---|
| `type` | `ONBOARDING_REMINDER` |
| `step` | `PROFILE` · `TERMS` · `NIN` · `NIN_LOCKED_OUT` · `LICENCE` · `VEHICLE` · `INSURANCE` · `ROADWORTHINESS` |
| `reason` | `NOT_DONE` · `RETRY` · `RESUBMIT` · `REJECTED` · `EXPIRED` · `LOCKED_OUT`. Context only; the app may ignore it. |
| `role` | `RIDER` or `DRIVER`: which side of the app the step belongs to |

The step is the **first** outstanding one, in the order above. Someone with both roles gets one reminder: the driver side if it has a step for them, otherwise the rider side.

**Bell entry:** `eventType: "ONBOARDING_REMINDER"`, with the same `step` and `role` (no `reason`).

### Copy (from `OnboardingReminderCopy`)

- **Title:** "You're almost there, {firstName}" ("You're almost there" when there's no first name). When locked out: **"Let's get you verified"**.
- **Body.** The goal is "to start driving" for drivers and "to start booking rides" for riders.

| step | reason | Body |
|---|---|---|
| `PROFILE` | any | "Complete your profile to start booking rides." / "Complete your driver profile to start driving." |
| `TERMS` | any | "Accept CoRyd's terms of service to start …" |
| `NIN` | `NOT_DONE` | "Verify your NIN to start …" |
| `NIN` | `RETRY` | "Your NIN check needs another try. Verify your NIN to start …" |
| `NIN_LOCKED_OUT` | `LOCKED_OUT` | "We couldn't verify your NIN in the app. Contact CoRyd support and we'll help you finish setting up your account." |
| `LICENCE`, `INSURANCE`, `ROADWORTHINESS` | `NOT_DONE` | "Upload your {driver's licence / insurance certificate / roadworthiness certificate} to start driving." |
| ″ | `RETRY` | "Your {doc} check needs another try. Retake it to start driving." |
| ″ | `RESUBMIT` | "CoRyd asked you to upload your {doc} again. Upload it to start driving." |
| ″ | `REJECTED` | "Your {doc} wasn't accepted. Upload a new one to start driving." |
| ″ | `EXPIRED` | "Your {doc} has expired. Upload a current one to start driving." |
| `VEHICLE` | `NOT_DONE` | "Add your vehicle and its registration to start driving." |
| `VEHICLE` | `RESUBMIT` | "CoRyd asked you to update your vehicle registration. Do it now to start driving." |
| `VEHICLE` | `REJECTED` | "Your vehicle registration wasn't accepted. Update your vehicle details to start driving." |

### What to open

**If `role` isn't the role the user is signed in as, switch role first** (`POST /identity/auth/roles/switch`; see [multi-role-auth-fe-guide.md](../multi-role-auth-fe-guide.md)). Then:

| `step` | Open | Call to finish it | Current state |
|---|---|---|---|
| `PROFILE` | Profile setup | Rider: `PUT /profile/riders/me`. Driver: `PUT /profile/drivers/me` | `GET /profile/riders/me` / `GET /profile/drivers/me` |
| `TERMS` | Terms screen | `POST /identity/auth/accept-terms` | `GET /identity/auth/onboarding-status` |
| `NIN` | NIN check | Rider (and a driver with `ninLookup: false`): Smile ID `POST /profile/identity-verification/session`. Driver with `ninLookup: true`: `POST /profile/drivers/me/nin-lookup` | `GET /profile/identity-verification/me`, or for a driver `GET /profile/drivers/me` (`ninStatus`, `ninTriesLeft`) |
| `NIN_LOCKED_OUT` | **Support / contact screen.** Not the NIN flow: it would fail with "Too many attempts…" | none | as for `NIN` |
| `LICENCE` | Licence check | `POST /profile/drivers/me/licence-check/session` | `GET /profile/drivers/me` (`licenceAttemptsLeft`, `licenceRetryReason`) |
| `VEHICLE` | Vehicle screen | `POST /profile/vehicles/drivers/{driverId}` or `PUT /profile/vehicles/{vehicleId}` | `GET /profile/drivers/{driverId}/verification-status` |
| `INSURANCE`, `ROADWORTHINESS` | Documents screen, that document | `POST /profile/drivers/me/documents` | verification-status `documents[]`, `resubmissionRequests[]` |
| unknown value | Onboarding checklist | | |

### Cadence (so repeats make sense)

Counted in **Lagos days** from the **anchor**: when the person signed up, or last made onboarding progress, whichever is later. Progress means accepting terms, any change to the profile, a NIN or licence check, or a vehicle or document change, including an ops decision on any of them.

| Days after the anchor | Reminder |
|---|---|
| 0 (sign-up or progress day) | none |
| 1–14 | every day |
| after that | every 3 days (day 17, 20, 23…) until they finish |

- At most **one per person per Lagos day**, on all devices.
- 14 and 3 are ops settings.
- **Quiet hours** are 21:00–07:00 Lagos. A reminder is never pushed inside them; a reminder time set inside them waits until 07:00.

### Edge cases

- **The push ignores the announcements switch** (`promotionalOptIn`). It's a service message about the person's own account. There's no opt-out for it, and no SMS or WhatsApp is sent.
- **No device token:** the bell entry is still written.
- The step can change between reminders (insurance one day, roadworthiness the next). Always route on the `step` in the push you received.
- The person may already have finished the step by the time they tap. Load the current state and show the checklist if nothing is left.

---

## 4. Identity (NIN + selfie) decisions by ops

**What triggers it:** ops approves, rejects or gives tries back on someone's identity check from the console. This applies to riders, and to drivers on either NIN path. Smile ID's own automatic results still send **no push**, as before.

### Pushes (`NotificationEventListener.onIdentityVerificationChanged`)

| Ops action | `type` | Title | Body |
|---|---|---|---|
| Approve (identity now verified) | `IDENTITY_VERIFICATION_APPROVED` | "Identity verified" | "Your identity has been verified. You're all set." |
| Approve, driver on the NIN lookup whose licence isn't approved yet | `IDENTITY_VERIFICATION_APPROVED` | "NIN confirmed" | "Your NIN has been confirmed. Finish verifying your licence to complete your identity check." |
| Reject | `IDENTITY_VERIFICATION_REJECTED` | "Identity check needs attention" | **the reason ops typed, word for word** (up to 500 characters) |
| Reset tries | `IDENTITY_VERIFICATION_RETRY` | "You can verify your identity again" | "We've reset your identity check. Open the app to try again." |

No `referenceId`. On tap, open the identity or verification screen and refetch.

### What `GET /profile/identity-verification/me` returns afterwards

Used by riders, and by drivers on the NIN + selfie check.

```json
{ "userId": "…", "status": "REJECTED", "ninLast4": "5040", "submittedAt": "…", "decidedAt": "…",
  "rejectionReason": "The name on your NIN doesn't match your profile - update your profile name or contact support",
  "checkInProgress": false, "retryReason": null, "attemptsLeft": 4 }
```

| After | `status` | Other fields | Show |
|---|---|---|---|
| Ops approve | `APPROVED` | `rejectionReason: null` | Verified. Booking or posting unlocks. The profile name becomes read-only (driver: `nameVerified: true`). |
| Ops reject | `REJECTED` | `rejectionReason` = the ops reason | The reason as-is, plus **Try again** (a new Smile ID check) while `attemptsLeft > 0` |
| Ops reset tries | **unchanged** (`PENDING` or `REJECTED`) | `attemptsLeft` back to **5** | Hide "contact support" and show **Verify** again |

**Drivers on the NIN lookup** (`GET /profile/drivers/me`, `ninLookup: true`):
- `ninStatus` is one of `PENDING` · `CHECKING` · `MATCH` · `IN_REVIEW` · `REJECTED`.
- After ops approves, it reads `MATCH`. After ops rejects, `REJECTED`.
- `ninTriesLeft` goes back to **3** after a reset.

### Tries and lock-out

- **NIN + selfie:** 5 tries (`attemptsLeft`). With none left, starting a check fails with `400 Too many attempts - contact CoRyd support to verify your identity`.
- **Driver NIN lookup:** 3 tries (`ninTriesLeft`). With none left: `400 You've used all your NIN tries - contact CoRyd support to finish checking your NIN`.
- At 0 tries, show **contact support**, not the check. Only ops can give tries back. The onboarding reminder for this case is `NIN_LOCKED_OUT`.
- An ops rejection doesn't use a try.
- `UNREADABLE` and `TRY_LATER` retries don't use a try either.
- Other start errors are unchanged:
  - `Your identity is already verified`;
  - `Your identity check is with our team - we'll let you know once it's done` (status `IN_REVIEW`).

### Edge cases

- **Ops can reverse its own approval.** An approval ops made can later be rejected, and the app gets a `_REJECTED` push after an `_APPROVED` one. An approval Smile ID made can't be reversed, except an unreviewed partial name match. Always refetch; never cache "verified forever".
- **An approve can arrive while the app shows `IN_REVIEW`, `REJECTED` or a Smile ID retry.** Refetch on the push.
- A rejection reason can be long. Wrap it; don't truncate.

---

## 5. Driver documents

The four items are `GOVERNMENT_ID` ("Driver's licence"), `VEHICLE` ("Vehicle registration"), `INSURANCE` ("Insurance certificate") and `ROADWORTHINESS_CERT` ("Roadworthiness certificate").

The driver app reads them from `GET /profile/drivers/{driverId}/verification-status` (the driver's own user id). The licence detail is on `GET /profile/drivers/me`.

### 5.1 Pushes (all also emailed when the driver has an email)

| Ops action | `type` | Title | Body |
|---|---|---|---|
| Approve one document, or Approve Driver | `VERIFICATION_ITEM_APPROVED` | "{Label} approved" | "Your {label} has been approved." |
| **Request resubmission** (new) | `VERIFICATION_ITEM_REJECTED` | **"Please upload your {label} again"** (e.g. "Please upload your insurance certificate again") | the reason ops typed (up to 255 characters) |
| Reject, or Save Changes with a flag | `VERIFICATION_ITEM_REJECTED` | "{Label} needs attention" | the flag's note (or "Your {label} was not accepted. Please review and resubmit.") |
| Ops edits plate, make, model or year of an approved car (new) | **`VERIFICATION_ITEM_IN_REVIEW`** | "Vehicle registration being re-checked" | "We've updated your vehicle details and are checking your vehicle registration again. You can post trips once it's approved." |
| Ops gives licence tries back (new) | **`VERIFICATION_ITEM_RETRY`** | "You can verify your driver's licence again" | "We've reset your driver's licence check. Open the app to try again." |
| First time all four are approved | `DRIVER_APPROVED` | "You're verified!" | unchanged. **Not** sent again when a driver is re-approved after a resubmission or renewal. |

None of these has a `referenceId`. On tap, open the verification or documents screen. For `VERIFICATION_ITEM_RETRY`, open the licence check.

### 5.2 `resubmissionRequests[]` (new on verification-status)

```json
{
  "overallStatus": "REJECTED",
  "documents": [
    { "type": "INSURANCE", "status": "REJECTED",
      "rejectionReason": "The policy number is cut off - upload the full page",
      "expiresOn": "2027-03-01", "documentUrl": "…", "submittedAt": "…", "resubmitted": false }
  ],
  "resubmissionRequests": [
    { "item": "INSURANCE", "label": "Insurance certificate",
      "reason": "The policy number is cut off - upload the full page", "requestedAt": "2026-10-04T09:00:00Z" }
  ]
}
```

- Each entry lists a document ops **asked** the driver to upload again that hasn't been re-uploaded yet. It's empty (`[]`) when there are none.
- The document itself reads `status: "REJECTED"` with the same `rejectionReason`.
- A plain rejection (Reject or a flag) is **not** in `resubmissionRequests`. Show both the same way ("Upload again" with the reason). Use `resubmissionRequests` if you want a "CoRyd asked you to re-upload" banner.
- After the driver re-uploads, the document goes back to `IN_REVIEW` and leaves `resubmissionRequests`.
- Re-upload endpoints are unchanged:
  - insurance and roadworthiness: `POST /profile/drivers/me/documents`;
  - vehicle: `PUT /profile/vehicles/{vehicleId}`;
  - licence: a new Smile ID check, `POST /profile/drivers/me/licence-check/session`.

### 5.3 Posting while a document is open

A resubmission request can hit an **already-approved** document, which takes the approval back. The driver is then no longer approved for posting. `GET /trip/routes/drivers/me/posting-eligibility` and `POST /trip/routes/drivers/{driverId}` follow the existing document-grace rules ([driver-document-grace-fe-guide.md](../driver-document-grace-fe-guide.md)):

| Item sent back | `blockedReason` / post error |
|---|---|
| Licence | `IDENTITY_PENDING_APPROVAL` / "Your government ID is still being reviewed - you can post routes once it is approved" |
| Vehicle (resubmission, or an ops edit) | `VEHICLE_PENDING_APPROVAL` / "Your vehicle is still being reviewed - you can post routes once it is approved" |
| Insurance or roadworthiness | `DOCUMENTS_OVERDUE` / "Your document upload window has closed - upload your insurance and roadworthiness certificate to post routes again", **once the driver's 30-day grace window has run out**. A driver still inside the window (first route less than 30 days ago, or never posted) can keep posting. |

- Trips already posted are **not** cancelled.
- When the document is approved again, posting reopens. There's no second "You're verified!".
- Let `blockedReason` drive the screen, as today.

### 5.4 Things ops can now do that change what the driver sees

- **Approve a licence Smile ID failed** (Smile ID asked for a retake). The licence becomes `APPROVED`, with the normal `VERIFICATION_ITEM_APPROVED` push. The app should stop showing the retake prompt once the push arrives or on refetch.
- **A licence Smile ID approved is locked.** Ops can't flag it or send it back, except an approval on a partial name match that ops hasn't reviewed yet. In practice a Smile-approved licence won't get a resubmission request.
- **Ops decides before Smile ID answers.** Ops' decision stands, and a late Smile ID result changes nothing and sends nothing.
- **Vehicle edits by ops:**
  - Plate, make, model or year of an approved car: the vehicle goes to `IN_REVIEW` and the driver gets the `VERIFICATION_ITEM_IN_REVIEW` push. Posting is blocked if it's the primary vehicle.
  - Colour or seat count: no review and no push; the values just change.
- **Licence tries given back:** `licenceAttemptsLeft` goes back to 5 on `GET /profile/drivers/me`, and the driver gets the `VERIFICATION_ITEM_RETRY` push.

### 5.5 Document expiry reminders (`DOCUMENT_EXPIRING`)

This isn't a new type, but ops can now also send it by hand from the console. The automatic job runs every morning at **07:00 Lagos**.

| When | Title | Body |
|---|---|---|
| 14 days before, then 3 days before (both ops settings) | "Insurance certificate expiring soon" | "Your insurance certificate expires in 3 days. Upload the renewed one before then to keep posting trips." ("…expires tomorrow." / "…expires today.") |
| Expired | "Insurance certificate expired" | "Your insurance certificate expired on 2 Sep 2026. Upload a current one to post trips again." |

- The same copy is used for "Roadworthiness certificate".
- No `referenceId`. The data has **`documentType`** (`INSURANCE` or `ROADWORTHINESS_CERT`) and **`vehicleId`** (the vehicle the document is for; left out when there isn't one), and the bell entry has `documentType`. Open Verification Status on that document. Older pushes and bell entries have neither: open Verification Status as before.
- Ops "Notify" is limited to once per driver per 24 hours, and the automatic reminders count towards that.
- An expired document reads `status: "EXPIRED"`. The driver can't post new trips until a current one is approved. Trips already posted go ahead, and the account is **not** suspended.
- Only insurance and roadworthiness are tracked. **Please make the expiry date required on upload:** documents uploaded without one can't be tracked or reminded.

---

## 6. Account actions: suspension, sign-out everywhere, deletion, edits

### 6.1 How sessions end

Ops can **suspend** an account, **sign it out everywhere**, or **delete** it. Each one cuts off every token that account already has, on **every** service, **within about a second**. The app doesn't have to wait for the access token's 24-hour expiry.

**Any authenticated call** with a cut-off token gets:

```json
HTTP 401
{ "success": false, "message": "This account has been suspended. Contact CoRyd support.", "data": null, "…": "…" }
```

or

```json
HTTP 401
{ "success": false, "message": "Your session has ended. Please sign in again.", "data": null, "…": "…" }
```

(`TokenRevocationRegistry.SUSPENDED_MESSAGE` / `SIGNED_OUT_MESSAGE`, returned by every service's `JwtFilter`.)

| Action | Calls with the old token | `POST /identity/auth/refresh-token` | `POST /identity/auth/login` |
|---|---|---|---|
| **Suspend** | `401 This account has been suspended. Contact CoRyd support.` | `401` same message | `401` same message |
| **Reinstate** (after a suspension) | `401 Your session has ended. Please sign in again.` (old tokens never come back) | `400 This session is no longer valid - please sign in again` | works: a fresh sign-in is required |
| **Sign out everywhere** | `401 Your session has ended. Please sign in again.` | `400 This session is no longer valid - please sign in again` | works straight away |
| **Deleted** | `401 Your session has ended. Please sign in again.` | `400 Invalid or expired refresh token` | `400 Incorrect phone number or password` (the account is gone) |

**What the app should do:**
1. On a `401` whose `message` is one of the two strings above, **don't** try a refresh; it can't succeed. Clear the access token, the refresh token and any **biometric-stored refresh token** ([biometric-signin-fe-guide.md](../biometric-signin-fe-guide.md)). Unregister the FCM token if you normally do on sign-out (it will fail with the dead token; that's fine, see below). Close the trip WebSocket.
2. **Suspended:** show a blocking screen with the message and a **Contact support** action. Don't offer sign-in as the way forward; signing in returns the same message.
3. **Session ended:** go to sign-in and show the message as a banner.
4. On a refresh that fails with any `400` above, sign out as today.
5. Match on the exact message, and prefer that over status alone. Some older "only your own" checks still return `401` with other text (`You can only view your own payments`, `You can only view your own earnings`). Those aren't sign-outs.

**Push tokens:** after a **suspension**, **sign out everywhere** or a **password reset**, notification-service drops every device token the account registered before that moment, so the phone stops getting the account's pushes even though the app couldn't unregister. The `SIGNED_OUT_EVERYWHERE` push is sent first. A phone that signs in again registers its token afresh and keeps it (also if it signed in again before the drop ran). Plain sign-out, a token refresh and a reinstate drop nothing. The bell is untouched.

**Edge cases:**
- **Public endpoints** (sign-in, refresh, OTP) still work for a request that carries a dead token; the token is just ignored. Don't treat a `200` on a public call as "still signed in".
- **Trip WebSocket:** a dead token is refused at connect. If the socket drops and reconnecting fails, check with an authenticated REST call to get the message.
- If a token isn't cut off in time, trip-service also refuses a suspended user's actions with `400 This account is suspended. Contact CoRyd support.` (posting, booking, and `unavailableReason` on the ride screen). Show it as-is.
- **A trip already under way is never interrupted by a suspension.** The person's app will still get 401s, so the live-trip screen must cope with losing the session mid-trip: show the message rather than crash.

### 6.2 Pushes around account actions

| Action | Person affected | Push |
|---|---|---|
| Sign out everywhere | the person | `SIGNED_OUT_EVERYWHERE`, "You've been signed out" / "For your security, CoRyd signed you out on all your devices. Sign in again to carry on." Sent only within 15 minutes of the action, then the phones' push tokens are dropped (6.1). On tap, open sign-in. |
| Suspend | the person | **No push** about the suspension; the app learns it from the next 401. Their phones' push tokens are dropped (6.1), so the pushes below reach the bell but usually not the phone. Their upcoming bookings and routes are cancelled, though, and those pushes **do** arrive: |
| ″ suspended rider | the rider | `BOOKING_CANCELLED`, "Booking cancelled" / "Your booking couldn't go ahead and has been cancelled. Your payment has been refunded." Booking reads `CANCELLED_RIDER`. |
| ″ suspended rider | the driver of each of those trips | `BOOKING_CANCELLED`, **"A seat opened up"** / "A rider's booking on your route was cancelled - the seat is available again." (never says why) |
| ″ suspended driver | the driver | `ROUTE_CANCELLED`, "Route cancelled" / "Your route was cancelled and N bookings were refunded." |
| ″ suspended driver | each rider booked on their routes | `BOOKING_CANCELLED`, "Your booking couldn't go ahead and has been cancelled. Your payment has been refunded." |
| Reinstate | the person | No push |
| Delete | the person | No push |

**Unpaid seat holds** of a suspended rider are just released. No push.

### 6.3 Details ops can edit

| Edit | What the person sees | Push |
|---|---|---|
| First name, last name, email | The new values on their profile screens (`/profile/riders/me`, `/profile/drivers/me`). The name in the JWT and session updates on the next refresh or sign-in. A name verified by a NIN check stays read-only for the person, but ops can correct it. | none |
| Phone | Ops **can't** change it | — |
| Vehicle: plate, make, model, year of an approved car | Vehicle `IN_REVIEW`; posting blocked until approved (section 5.4) | `VERIFICATION_ITEM_IN_REVIEW` |
| Vehicle: colour, seats | New values | none |

**Refetch profile screens on resume.** Don't rely on values cached at sign-in.

---

## 7. Trips and bookings cancelled or resolved by ops

There are no new push types. The existing pushes now have ops wording. The reason ops typed is **never** shown in the apps.

**Who cancelled: `cancelledBy` on `GET /trip/bookings/{bookingId}` (new).** The status can't tell you: an ops or platform cancellation reads `CANCELLED_DRIVER`, and an ops cancel with the rider at fault reads `CANCELLED_RIDER`. Word the booking from `cancelledBy` instead:

| `cancelledBy` | Means | Suggested wording |
|---|---|---|
| `RIDER` | The rider cancelled, or closed their account | "Cancelled by you" (rider) / "Cancelled by the rider" (driver) |
| `DRIVER` | The driver cancelled the trip, or closed their account | "Cancelled by the driver" |
| `CORYD` | CoRyd support (7.1, 7.2, 7.3), a suspension, a trip the driver never started, or a payment that arrived too late | "Cancelled by CoRyd" |
| `null` | The booking isn't cancelled | — |

Older app builds ignore the field and keep wording from the status.

### 7.1 Ops cancels a whole trip (`cancelledBy: OPS_TRIP`)

| | Rider app | Driver app |
|---|---|---|
| Push | `BOOKING_CANCELLED` (`referenceId` = bookingId), "Booking cancelled" / **"CoRyd support cancelled this trip. You've been fully refunded."** (no refund sentence if they hadn't paid) | `ROUTE_CANCELLED` (`routePostId`), "Route cancelled" / **"CoRyd support cancelled your route and N bookings were refunded."** ("…your route." when nobody had paid) |
| State | Booking `CANCELLED_DRIVER`. The trip leaves search and the group chat closes. | Route `CANCELLED` |
| Money | Then `REFUND_ISSUED`, "Refund issued" / "You've been refunded ₦X for a cancelled booking." | If the driver had been paid at charge time, `DRIVER_OWED_CHANGED`, "A trip you were paid for was refunded" / "₦Y will be taken from your next earnings. You now owe ₦Z." |

Riders always get a full refund; ops can't blame the rider for a whole trip.

### 7.2 Ops cancels one booking (`cancelledBy: OPS`)

| Fault ops chose | Rider push body | Booking status |
|---|---|---|
| `RIDER`, late-cancellation fee kept | "CoRyd support cancelled your booking. You'll be refunded, less the late-cancellation fee." | `CANCELLED_RIDER` |
| `RIDER`, fee waived or not due | "CoRyd support cancelled your booking. You've been fully refunded." | `CANCELLED_RIDER` |
| `DRIVER` / `PLATFORM` | "CoRyd support cancelled your booking. You've been fully refunded." | `CANCELLED_DRIVER` |
| Not paid yet (a `HELD` or `RESERVED` seat) | **no push**. The hold disappears and the payment screen's hold ends | `CANCELLED_RIDER` |

- The title is always "Booking cancelled", the type `BOOKING_CANCELLED`, and `referenceId` the booking id.
- **Driver:** `BOOKING_CANCELLED` with the same booking id, **"A seat opened up"** / "CoRyd support cancelled a booking on your route - the seat is available again." The manifest drops the rider, and the trip is bookable again if it was full.

**Edge case: ops can cancel a seat at any stage.** That includes `EN_ROUTE_TO_PICKUP`, `AT_PICKUP`, and **`IN_PROGRESS` (rider already aboard)**.
- The rider's live-trip screen must react to `BOOKING_CANCELLED` (or the booking status on refetch) by leaving the live view.
- The driver's manifest and pickup flow must drop the rider. A pickup code entered after that fails.

### 7.3 Stuck trips resolved by ops

A trip still `IN_PROGRESS` 3 hours past its expected arrival (an ops setting) is "stuck". Ops ends it in one of two ways:

| Outcome | Rider app | Driver app |
|---|---|---|
| `COMPLETED` | Riders aboard get the normal `TRIP_COMPLETED`, "Trip completed" / "Your trip is complete. Rate your driver!"; the receipt is available. Riders never picked up are either refunded (the same "CoRyd support cancelled this trip…" push) or marked `NO_SHOW` with **no push**. | `TRIP_COMPLETED` per rider aboard; earnings settle as usual. The route reads `COMPLETED`, so take it off the active-trip screen. |
| `CANCELLED` | As 7.1 | As 7.1 |

The driver's app may still think the trip is live; the driver probably forgot to tap Complete. Refetch the active trip on resume and on these pushes.

### 7.4 Booking window and `bookableUntil`

Unchanged from [leaving-now-booking-window.md](leaving-now-booking-window.md): use `bookableUntil` and `holdExpiresAt`, never `departureTime` plus a fixed number. The window after departure (default 10 minutes) is now an **ops setting** (`booking.grace-minutes`, 0–60), so it can change with no app release.

---

## 8. Safety and support

### 8.1 SOS

**Nothing changes in the apps.**
- Pressing SOS still sends "Emergency alert sent" and texts the emergency contacts.
- Ops now acknowledges, escalates and resolves SOS incidents in the console, and the ops phones are re-alerted at 5, 15 and 30 minutes until someone acknowledges. Neither the person who pressed SOS nor anyone else on the trip gets a push for any of that.
- Ops calls the person by phone.
- Answering an "Are you OK?" check after it was escalated still works.

### 8.2 Reports: `ISSUE_REPORT_UPDATED` (new)

Reports are what people send from **Report an issue** (`POST /trip/bookings/report-issue`). When ops closes one **with a message to the reporter**:

| Ops action | Push and bell entry (reporter only) |
|---|---|
| Resolve (a message is always required) | `ISSUE_REPORT_UPDATED`, **"We've looked into report ISS-00000001"** / the ops message |
| Dismiss with a message | `ISSUE_REPORT_UPDATED`, **"Update on report ISS-00000001"** / the ops message |
| Dismiss with no message, assign, internal note | nothing |

- `referenceId` / bell `bookingId` is the booking the report was about, or **absent** for a report filed from the account with no trip. On tap, open that trip's details if it's set, otherwise the bell entry.
- When the report has no reference number the title reads "…your report".
- The other party is **never** told about the report. A refund or suspension that follows reaches people through that action's own notifications.
- The report's status isn't exposed to the apps. Nice to have: show the bell entry's text on the report screen if you keep one.

### 8.3 Ratings hidden by ops

- **No push and no API change.** A hidden rating is left out of every average: search results, the trip and booking screens, receipts.
- The person's average can move with no new trips. Don't cache it across sessions.
- The rater still sees their own rating on their receipt.

---

## 9. Money

| Ops action | Rider app | Driver app |
|---|---|---|
| **Manual refund** (completed) | `REFUND_ISSUED`, "Refund issued" / "You've been refunded ₦X for a cancelled booking." (existing copy, even if the booking wasn't cancelled). In payment history (`GET /payment/payments/riders/{riderId}/history`): a refund row, `status: REFUNDED`, **`refundKind: CANCELLATION`** (no new kind). | `DRIVER_OWED_CHANGED`, "A trip you were paid for was refunded" / "₦Y will be taken from your next earnings. You now owe ₦Z." (Y is the driver's share.) The owed history gets a `REFUNDED_TRIP` entry. Over ₦10,000 owed blocks posting (`AMOUNT_OWED`). |
| Manual refund of a whole seat not yet travelled | As above. **The booking stays active**: same status, pickup code still valid. | The driver isn't paid for that seat when the trip completes |
| Manual refund waiting for approval, rejected, cancelled or failed | nothing (no money moved) | nothing |
| Refund retry (a cancellation refund Paystack had refused) | The pending refund in payment history turns `REFUNDED`; `REFUND_ISSUED` push | As for a refunded trip |
| Payout retry | — | **No push.** The payout in Earnings turns paid when Paystack settles it. If the bank account no longer checks out, ops will ask the driver to **re-add their bank account** (Payout Setup). |
| **Debt adjustment** | — | **No push.** The owed history (`GET /payment/earnings/drivers/{driverId}/owed/history`) and the driver statement (`…/statement`) get an **`OPS_ADJUSTMENT`** entry |
| Payout account reveal | nothing | nothing |

### `OPS_ADJUSTMENT` entry

```json
{ "id": "…", "type": "OPS_ADJUSTMENT", "amount": -500.00, "balanceAfter": 140.00,
  "bookingId": null, "reference": "ops:7d1c…", "note": "Paid in cash at the Ikeja office", "occurredAt": "…" }
```

- **`amount` is signed.** `+` adds to what the driver owes (a debit); `−` takes it down (a credit, never below zero).
- **`note` is the reason ops typed, and the driver can read it.** Show it as the entry's description.
- Suggested label: "Adjustment by CoRyd".
- `bookingId` is `null`.
- Owing more than ₦10,000 blocks posting, and a credit can unblock it. Refresh `GET /payment/earnings/drivers/{driverId}/owed` on resume.

### `403` on another user's booking payment

`GET /payment/payments/bookings/{bookingId}` (the receipt / Pay Now status) answers only:
- the rider who booked;
- the driver of that booking's trip.

Anyone else gets `403 You can only view payments for your own bookings`. A booking with no payment is still `404 No payment found for this booking`. The apps only read their own bookings, so nothing should change. If it ever appears, show a generic error and don't sign out.

---

## 10. Broadcasts

Ops can send one message to many riders and/or drivers: a road closure, a service notice, an announcement.

| | |
|---|---|
| Push `type` | `BROADCAST` |
| Push data | `referenceId` = the broadcast id. **No deep link.** |
| Title / body | As ops wrote them (title up to 80 characters, body up to 1,000) |
| Bell | `eventType: "BROADCAST"`, `bookingId: null`, `routePostId: null`. Written for everyone in a push broadcast, **even without a device token** |
| On tap | Open the notifications screen, or a full-text view of that entry. Bodies can be long. |
| Other channels | Ops may also send SMS ("CoRyd: {title} - {body}"), email or WhatsApp. Nothing for the app. |

**Who receives it:**
- **Announcements switch off** (`promotionalOptIn = false`): the person is skipped, **unless** ops marked the broadcast **urgent** (service or safety notices).
- **WhatsApp switch off** (`whatsappOptIn = false`): only WhatsApp is skipped. Push, SMS and email still go.
- **Quiet hours, 21:00–07:00 Lagos:** non-urgent broadcasts wait until 07:00, and one still sending at 21:00 pauses until 07:00. Urgent broadcasts ignore quiet hours.
- Suspended accounts never receive one.

**The app's settings screen:** the announcements switch must write `promotionalOptIn`:

```
PUT /notification/preferences/users/{userId}?promotionalOptIn=false
```

Either flag can be sent alone; one you don't send keeps its value. Both default to `true`. The response is the current preferences: `{ userId, promotionalOptIn, whatsappOptIn, … }`.
- **To show the switches' current state, use the new `GET /notification/preferences/users/{userId}`.** It returns the same shape without changing anything (the defaults, both `true`, if the person has never set them; nothing is stored). The old workaround, a PUT with no parameters, still works.
- `{userId}` must be the signed-in user's own id, or the call gets `403` (section 12).
- The onboarding reminders (section 3) and every trip, verification and account notification **ignore** this switch.

---

## 11. Settings ops can now change without an app release

Platform Settings in the console change these values at runtime, and every service picks the change up within seconds. There is no settings endpoint for the apps; where a value matters on screen, the endpoint in the table carries it. **Don't hard-code them.**

| Setting (default) | Where it shows in the apps | What to do |
|---|---|---|
| `booking.grace-minutes` (10) | `bookableUntil`, seat hold length, search | Use `bookableUntil` and `holdExpiresAt` only |
| `cancellation.late-fee-percent` (25%) and `cancellation.free-window-hours` (2) | The fee charged when a rider cancels | Read `GET /trip/bookings/{bookingId}/cancellation-quote` when the cancel confirmation opens: it gives the fee, the refund, `freeUntil` and the current `feePercent` / `freeCancellationWindowHours` ([card-payments-fe-guide.md](../card-payments-fe-guide.md) section 3). Anywhere without a booking, word it generally. |
| `commission.percent` (5%) | The driver's earnings breakdown | Read `GET /payment/earnings/commission-rate` |
| `documents.expiry-reminder-days` (14) and `documents.final-reminder-days` (3) | When `DOCUMENT_EXPIRING` arrives | Nothing |
| `onboarding.*` | When and how often `ONBOARDING_REMINDER` arrives (08:00 Lagos, daily for 14 days, then every 3) | Nothing |

---

## 12. Privacy and security

- **ID masking is for ops only.** In ops responses, NIN, licence number, date of birth and bank account numbers are masked (`•••• 5040`, a birth year instead of the date). **The driver still sees their own values in full:**
  - `GET /profile/drivers/{driverId}/verification-status` returns the unmasked `licenceNumber` and `dateOfBirth` only when `driverId` is the caller's own id. Anyone else gets `licenceNumber: null`, `dateOfBirth: null` and the masked `licenceNumberMasked` and `birthYear`. Confirmed in `DriverProfileController.view`.
  - `GET /profile/drivers/me` still returns `dateOfBirth` and `idNumber`.
  - Both responses also carry the new `licenceNumberMasked` and `birthYear` fields. The app can ignore them.
- **New `403`s for reading other people's data:**
  - `GET /profile/drivers/{driverId}/documents` returns `403 You can only view your own documents` unless it's the driver themself.
  - `GET /payment/payments/bookings/{bookingId}` returns `403` as in section 9.
  - **Notifications, preferences and device tokens act only for the signed-in user.** Before this, any signed-in user could read another person's bell, change their switches, or register their own phone's push token on someone else's account (and so receive that person's pushes). Now the `{userId}` in the path must be the user in the token:

    | Endpoint | Someone else's id |
    |---|---|
    | `GET /notification/notifications/{userId}` | `403 You can only manage your own notifications` |
    | `GET /notification/notifications/{userId}/unread-count` | `403` (same message) |
    | `POST /notification/notifications/users/{userId}/read-all` | `403` (same message) |
    | `POST /notification/notifications/{notificationId}/read` | `404 Notification not found` when the notification is someone else's, exactly as for an id that doesn't exist |
    | `GET` (new) and `PUT /notification/preferences/users/{userId}` | `403` (same message) |
    | `POST /notification/device-tokens/users/{userId}` | `403` (same message); the token is not registered |
    | `DELETE /notification/device-tokens/users/{userId}?fcmToken=…` | `403` (same message); nothing is removed |

    - **What the apps must do:** always use the id of the user whose access token you're sending. A rider who also drives has one token per app, but the user id is the same, so nothing changes there. If the app caches a user id across sign-ins, refresh it when someone else signs in on the phone.
    - **Same phone, new account still works.** When B signs in on a phone where A was signed in, registering the phone's FCM token for B (with B's token and B's id) moves the token from A to B, as before. Removing the token on sign-out is still recommended.
    - **The delete must run before the session is cleared.** It needs the user's own token, so call it while still signed in (a call after sign-out gets `401`).
    - Ops admins can't use these endpoints for other people either; the console never needed them.

  All of these are `403` (or `404`), deliberately not `401`, so the apps don't treat them as sign-outs.
- **Ops-only security changes don't touch rider or driver tokens:**
  - two-factor sign-in;
  - 15-minute access tokens and 12-hour sessions;
  - "Your session timed out - sign in again".

  Rider and driver access tokens still last **24 hours** and refresh tokens **30 days**.

---

## 13. WhatsApp and SMS

- **OTP over WhatsApp:** `POST /identity/auth/resend-otp` with `{ "phone": "…", "channel": "WHATSAPP" }` (`SMS` is the default; `VOICE` also works).
  - WhatsApp OTPs now use an approved WhatsApp template.
  - **While WhatsApp is switched off or the template isn't approved yet (the case today), the code goes by SMS.** It also goes by SMS if WhatsApp refuses it.
  - Keep the "Send on WhatsApp" option if you have it, but word what follows as "We've sent you a code". Don't promise it will arrive on WhatsApp.
- **`whatsappOptIn`** (default `true`) is a preference the app can expose as a "Messages on WhatsApp" switch: `PUT /notification/preferences/users/{userId}?whatsappOptIn=false`.
  - It only changes the **channel** of the person's shared-trip links and SOS alerts to their emergency contacts, and of WhatsApp broadcasts. Those messages then go by SMS (or are skipped, for a WhatsApp-only broadcast).
  - It doesn't affect push, email, or an OTP the person explicitly asks to get on WhatsApp.
  - Optional (Nice). There's nothing to show while WhatsApp is off on the backend.

---

## 14. What the apps can ignore

- Ops notes on SOS incidents, reports, ratings, refunds and trips. The apps never receive them.
- SOS re-alerts to ops (`SOS_OPS_REALERT`, WhatsApp, SMS and email to ops phones only).
- The verification officers' morning digest email (`VERIFICATION_DIGEST`).
- `reason` in `ONBOARDING_REMINDER` data. It's optional context; route on `step`.
- `licenceNumberMasked` and `birthYear` on the driver's own verification-status.
- The cancellation log, audit log, ops two-factor sign-in, the ops live stream and payout-account reveals.
- Corridors and the pricing monitor.

---

## 15. How to test on dev

**Set-up**
- **API:** `https://api-dev.coryd.ng`. **Ops console:** `https://admin.coryd.ng`; ask the backend team for an ops test login with the role you need.
- **Seed accounts only, never real users.**
  - Seed riders are `08000000101`–`08000000105`; the commonly used one is Amaka Obi, ref `R-0DE4B2`.
  - Seed drivers are `08000000201`–`08000000210`; the commonly used one is ref `D-7C34F1`.
  - They use the dev seed password (`SeedFixtures.SEED_PASSWORD`; ask the backend team).
  - Dev uses a fixed OTP, `000333` ([deployment-dev.md](../deployment-dev.md)).
  - Seed accounts **start verified**. To test onboarding, sign up a new account with a test number.
- **Smile ID on dev is the sandbox.** Every NIN and licence check **approves**, for any input, and tries aren't counted, so lock-out never happens on its own on dev ([smile-id-verification.md](../smile-id-verification.md)).
- In the console, people are found by name, phone or ref (`R-…`, `D-…`, `T-…`, `B-…`).
- **Put everything back afterwards:**
  - approve back what you rejected;
  - reinstate what you suspended;
  - restore names you edited.

| To see | Do this in the console | Expect in the app |
|---|---|---|
| **Onboarding reminder** | Use a test account that signed up **yesterday or earlier** (day 0 is skipped) and has a step left, e.g. a driver with no insurance uploaded. As a super admin: Platform Settings → onboarding reminders → **Preview** (dry run: lists who would get what, sends nothing), then **Send now**. Not between 21:00 and 07:00 Lagos: refused. Repeat sends the same day reach nobody new. | `ONBOARDING_REMINDER` with `step: INSURANCE`, `role: DRIVER`; bell entry with `step`/`role`; tap opens Documents → insurance |
| `NIN_LOCKED_OUT` | Can't happen on its own on dev (sandbox). Ask the backend to set a test account's tries to the limit. | Body "We couldn't verify your NIN…", tap opens support |
| **Identity reject** | Riders → Identity Verification → open the person → **Reject** with a reason. A check Smile ID approved can't be rejected, so on dev either (a) open the NIN check in the app and leave before the selfie (it stays Pending), or (b) **Approve** first (ops approval), then **Reject**. | `IDENTITY_VERIFICATION_REJECTED` with the reason; `GET /profile/identity-verification/me` → `REJECTED` + `rejectionReason`; Try again shown |
| Identity approve | **Approve** (a note is required if it was rejected) | `IDENTITY_VERIFICATION_APPROVED` "Identity verified"; status `APPROVED`, booking unlocked |
| Identity reset tries | **Reset tries** (needs a used try; on dev ask the backend to seed one) | `IDENTITY_VERIFICATION_RETRY`; `attemptsLeft: 5` |
| **Document resubmission** | Drivers → Verification Queue (or the driver's profile) → document → **Request resubmission** with a reason, e.g. on seed driver D-7C34F1's insurance | "Please upload your insurance certificate again" (`VERIFICATION_ITEM_REJECTED`); verification-status shows the document `REJECTED` and a `resubmissionRequests[]` entry; posting blocked if past grace. Re-upload → `IN_REVIEW`; ops **Approve** → `VERIFICATION_ITEM_APPROVED`, no second "You're verified!" |
| Vehicle sent back | Driver profile → Edit vehicle → change the plate (with a reason) | `VERIFICATION_ITEM_IN_REVIEW` "Vehicle registration being re-checked"; `VEHICLE_PENDING_APPROVAL` on posting-eligibility. Restore the plate and approve. |
| Expiry reminder | Drivers → Document Expiry → **Notify** on an expiring row (once per 24 h per driver) | `DOCUMENT_EXPIRING` with `documentType`; tap (or the bell entry) opens Verification Status on that document |
| **Suspend** | Rider or driver profile → **Suspend** with a reason, while the app is open | Within seconds the next call is `401 This account has been suspended. Contact CoRyd support.`; app shows the suspension screen; sign-in shows the same. Upcoming bookings get `BOOKING_CANCELLED`. |
| Reinstate | **Reinstate** with a reason | Old session: `401 Your session has ended…`; signing in again works |
| **Sign out everywhere** | **Sign out everywhere** with a reason, with the app signed in on two devices | `SIGNED_OUT_EVERYWHERE` push on both; next call `401 Your session has ended. Please sign in again.`; biometric unlock no longer works; signing in works. Afterwards a push to that account (e.g. a broadcast) reaches only the devices signed in again |
| Edit name | **Edit details** → last name | New name on the profile screen after refetch; in the session after refresh |
| **Cancel a trip** | Seed driver posts a trip, seed rider books and pays; Trips → the trip → **Cancel** (fault `DRIVER`) | Rider: "CoRyd support cancelled this trip. You've been fully refunded." then "Refund issued"; driver: "CoRyd support cancelled your route and 1 booking was refunded." |
| Cancel one seat | Trips → booking → **Cancel booking** (fault `RIDER`, with or without waive) | Rider: "CoRyd support cancelled your booking…" with fee wording; driver: "A seat opened up". Trip details say "Cancelled by CoRyd" (`cancelledBy: CORYD`), not "Cancelled by you" |
| Stuck trip | Hard to reach on dev (needs 3 h past arrival); ask the backend to back-date a test trip | `TRIP_COMPLETED` or cancellation pushes |
| **Report update** | File **Report an issue** from the app; Reports & Disputes → **Resolve** with a message to the reporter | `ISSUE_REPORT_UPDATED` "We've looked into report ISS-…" with the message |
| Hide rating | Ratings → **Hide** a rating of the seed driver | The driver's average changes on the next load; no push |
| Manual refund | Money → booking → **Refund** a small amount (under ₦50,000 runs at once) | Rider: "Refund issued", history row `REFUNDED`; driver: "A trip you were paid for was refunded" |
| Debt adjustment | Money → Driver earnings → **Adjust** (credit or debit, with a reason) | No push; `OPS_ADJUSTMENT` entry with the note in the owed history |
| **Broadcast** | Notifications → Broadcasts → audience `USERS` with the seed refs (e.g. `R-0DE4B2`), channel `PUSH`, **Preview**, then send. Try once with the announcements switch off (skipped), once **urgent** (delivered). | `BROADCAST` push; bell entry; tap opens notifications |
| WhatsApp OTP | Resend OTP with `channel: WHATSAPP` | Arrives by SMS (WhatsApp is off on dev); on dev with Termii off, use the fixed OTP |

---

## 16. Checklist for the apps

### Push types to register and route

| Type | New? | App | Open on tap | Priority | QA scenario |
|---|---|---|---|---|---|
| `ONBOARDING_REMINDER` | new | both | the screen for `step` (switch to `role` first); `NIN_LOCKED_OUT` → support; unknown → checklist | **Must** | Preview and send from Platform Settings for a day-1 driver missing insurance; tap opens insurance upload |
| `IDENTITY_VERIFICATION_APPROVED` | new | both | identity status | **Must** | Ops approve; status `APPROVED`, booking/posting unlocks |
| `IDENTITY_VERIFICATION_REJECTED` | new | both | identity status with reason + Try again | **Must** | Ops reject; reason shown word for word |
| `IDENTITY_VERIFICATION_RETRY` | new | both | identity check | **Must** | Ops reset tries; "contact support" replaced by Verify |
| `VERIFICATION_ITEM_REJECTED` (resubmission title) | reused | driver | documents → that item | **Must** | Request resubmission; reason and re-upload shown |
| `VERIFICATION_ITEM_APPROVED` | existing | driver | documents | Should | Approve after re-upload; banner clears |
| `VERIFICATION_ITEM_IN_REVIEW` | new | driver | vehicle status | Should | Ops edits plate; "being re-checked" and posting blocked |
| `VERIFICATION_ITEM_RETRY` | new | driver | licence check | Should | Ops gives licence tries back; check opens again |
| `SIGNED_OUT_EVERYWHERE` | new | both | sign-in | Must | Sign out everywhere; push arrives and app is at sign-in |
| `ISSUE_REPORT_UPDATED` | new | both | the trip (`referenceId`) or the bell | Should | Resolve a report with a message |
| `BROADCAST` | new | both | notifications | **Must** | Send to seed refs; bell entry and push |
| `BOOKING_CANCELLED`, `ROUTE_CANCELLED` (ops wording) | existing | both | booking / route | Should | Ops cancels a trip and a seat |
| `REFUND_ISSUED`, `DRIVER_OWED_CHANGED` (ops refunds) | existing | rider / driver | payment history / owed | Nice | Manual refund |
| `DOCUMENT_EXPIRING` (ops Notify) | existing | driver | Verification Status focused on `documentType` | Should | Notify from Document Expiry; push and bell open that document |
| any unknown `type` | | both | notifications | **Must** | — |

### Fields to read

| Field | Where | Priority | QA scenario |
|---|---|---|---|
| `step`, `role` on bell entries | `GET /notification/notifications/{userId}` | **Must** | Tapping an onboarding entry in the bell routes like the push |
| `cancelledBy` | `GET /trip/bookings/{bookingId}` | **Must** | Ops cancels a seat with the rider at fault: trip details say "Cancelled by CoRyd" |
| `reason`, `feeAmount`, `refundAmount`, `freeUntil` (new endpoint) | `GET /trip/bookings/{bookingId}/cancellation-quote` | Should | Ops sets the late fee to 30%: the cancel confirmation shows 30% and the matching refund with no app release |
| `documentType` on `DOCUMENT_EXPIRING` bell entries (and `documentType`, `vehicleId` in the push data) | bell / push | Should | Tapping the expiry entry opens that document |
| `rejectionReason`, `attemptsLeft` | `GET /profile/identity-verification/me` | **Must** | After ops reject and reset |
| `ninStatus`, `ninTriesLeft`, `licenceAttemptsLeft`, `nameVerified` | `GET /profile/drivers/me` | **Must** | Driver NIN reject and licence reset |
| `resubmissionRequests[]` | `GET /profile/drivers/{id}/verification-status` | **Must** | Request resubmission, then re-upload: entry appears then clears |
| `blockedReason` after a resubmission or vehicle edit | `GET /trip/routes/drivers/me/posting-eligibility` | **Must** | Posting blocked with the right screen |
| `OPS_ADJUSTMENT` (signed `amount`, `note`) | `…/owed/history`, `…/statement` | Should | Credit and debit both render |
| `promotionalOptIn`, `whatsappOptIn` | `GET` (read) / `PUT` (change) `/notification/preferences/users/{userId}` | Should / Nice | Switch off announcements: a non-urgent broadcast is skipped, an urgent one arrives |
| `bookableUntil`, `holdExpiresAt` | search, ride screen, booking | Should | Ops changes `booking.grace-minutes`; the app follows without a release |

### Error messages to handle

| Message | Status | Where | Do | Priority |
|---|---|---|---|---|
| `This account has been suspended. Contact CoRyd support.` | 401 | any authenticated call, refresh, login | clear the session; suspension screen with Contact support; no refresh attempt | **Must** |
| `Your session has ended. Please sign in again.` | 401 | any authenticated call | clear the session; sign-in with banner; no refresh attempt | **Must** |
| `This session is no longer valid - please sign in again` / `Invalid or expired refresh token` | 400 | refresh | sign out | Must (likely already) |
| `This account is suspended. Contact CoRyd support.` | 400 | post route, book, ride screen `unavailableReason` | show as-is | Should |
| `Too many attempts - contact CoRyd support to verify your identity` / `You've used all your NIN tries - contact CoRyd support to finish checking your NIN` | 400 | start the NIN check | show + support | Should |
| `Your vehicle is still being reviewed - you can post routes once it is approved` / `Your government ID is still being reviewed…` / `Your document upload window has closed…` | 400 | post route | show as-is (eligibility should have caught it) | Should |
| `You can only view your own documents` / `You can only view payments for your own bookings` | 403 | other people's data | generic error; **not** a sign-out | Nice |
| `You can only manage your own notifications` | 403 | bell, preferences, device-token calls with another user's id | **not** a sign-out; it means the app sent a stale or wrong user id - fix the id | Should |
| `Notification not found` | 404 | mark read on an id that isn't the user's | drop the entry and refetch the bell | Nice |

### Behaviour

- [ ] **Must:** a 401 with one of the two session messages clears the session (including the biometric token) and doesn't refresh.
- [ ] **Must:** the live-trip and booking screens cope with `BOOKING_CANCELLED` at any stage, including a rider already aboard.
- [ ] **Must:** profile, verification and identity screens refetch on resume and on the pushes above.
- [ ] **Should:** the announcements switch writes `promotionalOptIn`; its state is read with `GET /notification/preferences/users/{userId}`.
- [ ] **Should:** bell, preferences and device-token calls use the signed-in user's own id; the token is registered after sign-in and removed before the session is cleared.
- [ ] **Should:** no hard-coded booking window, fee percent, free window or commission.
- [ ] **Should:** expiry date required when uploading insurance or roadworthiness.
- [ ] **Nice:** WhatsApp OTP copy doesn't promise WhatsApp; optional WhatsApp switch.

---

## 17. Where the ops-console docs differ from the code

The code is right in every case below; this guide follows it.

1. **Bell path in broadcasts.md.** [broadcasts.md](../ops-console/broadcasts.md) §4 gives the bell as `GET /notification/{userId}`. The controller is `GET /notification/notifications/{userId}`, as [onboarding-reminders.md](../ops-console/onboarding-reminders.md) says.
2. **`referenceId` when there is nothing to reference.** [firebase-fcm.md](../firebase-fcm.md) shows `"referenceId": null` in push data. `FcmService` leaves the key out instead.
3. **Push types the ops docs don't name.**
   - [accounts.md](../ops-console/accounts.md) §9 gives the copy for sign-out everywhere, the vehicle re-check and the licence-tries reset, but not their types. They are `SIGNED_OUT_EVERYWHERE`, `VERIFICATION_ITEM_IN_REVIEW` and `VERIFICATION_ITEM_RETRY`.
   - [verification-queue.md](../ops-console/verification-queue.md) §13 says "Push `type`s are unchanged". That's true for approve, reject and resubmit only.
4. **Posting while an insurance or roadworthiness resubmission is open.** [verification-queue.md](../ops-console/verification-queue.md) §13 says "an approved driver can't post until the new upload is approved". In code that holds for the licence and vehicle. For insurance and roadworthiness, trip-service applies the document-grace rule: a driver still inside the 30-day window (or who has never posted) can keep posting.
5. **Copy not listed in onboarding-reminders.md.** The `VEHICLE` bodies ("CoRyd asked you to update your vehicle registration. Do it now…", "Your vehicle registration wasn't accepted. Update your vehicle details…") and the licence `RETRY` body ("…Retake it…") aren't in the doc. This guide quotes `OnboardingReminderCopy`.
6. **Manual refund push wording.** [money.md](../ops-console/money.md) §11 is accurate that the existing "…for a cancelled booking." copy is reused. Note that it is sent even when ops refunds part of a booking that stays active.
7. **Preferences can now be read with a GET.** `NotificationPreferencesController` used to have only `PUT /preferences/users/{userId}`; it now also has `GET /preferences/users/{userId}`, which reads the switches without changing them.
8. **Deleted account.** [accounts.md](../ops-console/accounts.md) §9 says sign-in "fails, since the account is gone". The exact responses are refresh `400 Invalid or expired refresh token` and login `400 Incorrect phone number or password`.
9. **Older "only your own" checks still answer `401`, not `403`.** [security.md](../ops-console/security.md) §7 notes that `You can only view your own payments` and `You can only view your own earnings` remain `401` (`PaymentHistoryService`, `EarningsService`). Apps that sign out on every 401 could sign someone out on a bad id. Match on the two session messages instead.

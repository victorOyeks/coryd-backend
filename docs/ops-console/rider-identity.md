# Ops Console: Rider Identity (NIN)

Sidebar: **Riders → Identity Verification**.

Everyone who has started the identity (NIN) check: riders (NIN + live selfie through Smile ID) and drivers (the NIN lookup, or the older NIN + selfie). Ops sees who is waiting, who Smile ID failed, who ran out of tries and who is verified; opens one person to see every Smile ID result; then **Approve**, **Reject** or **Reset tries**.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back with `success: false` and a `message` you can show as-is.
- **Who's recorded:** the reviewer on every action is the signed-in admin. You never send it; anything you send as `reviewedBy` is ignored.

---

## 0. The rules

These are enforced by the server. The screen should follow the `actions` block on the person (section 4), which says in advance what each button would do.

| # | Rule | What the server does |
|---|---|---|
| 1 | **What Smile ID approved stays approved.** | `reject` on a check Smile ID approved is a `400`. The one exception: an approval on a **partial name match** that hasn't been reviewed yet (it's on the partial-name-match list) can still be rejected; rejecting it takes it off that list. An approval **ops** made can be reversed with a reject. |
| 2 | **Ops can approve anything that isn't approved**: pending, in review, failed by Smile ID, locked out, or rejected. | Approving a check Smile ID **failed** or that's **locked out**, or one that was **rejected**, needs a `note` (`400` without). Every approval over a Smile ID result is recorded as an **override**: who, when, the note, and what Smile ID had said. |
| 3 | **A rejection always has a reason**, and the person sees it. | `reject` without `reason` is a `400`. |
| 4 | **Every action is in the audit log** (`history`): who, what, before and after, note or reason, when. | |
| 5 | **The person is told** about every ops action, by push notification. | See section 8. |

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Tabs **All / Pending / In Review / Failed / Approved / Locked Out** and their counts | `GET /identity-verification/people/counts` |
| Table: search, sorting, paging | `GET /identity-verification/people` |
| **View**: one person | `GET /identity-verification/people/{userId}` |
| Selfie / NIN photo for one Smile ID result | `GET /identity-verification/people/{userId}/checks/{checkId}/images` |
| **Approve** | `POST /identity-verification/people/{userId}/approve` |
| **Reject** | `POST /identity-verification/people/{userId}/reject` |
| **Reset tries** | `POST /identity-verification/people/{userId}/reset-attempts` |
| Audit log | `GET /identity-verification/people/{userId}/history` (also inside the person, as `history`) |

**Every action returns the whole person** (section 4), so the screen refreshes from the response.

The older calls still work: `GET /identity-verification/queue` (in review only), `POST /identity-verification/{userId}/decision` (`{ "approve": true|false, "reason": "…" }`, same rules: on an approval `reason` is kept as the note), and the partial-name-match list (`GET /identity-verification/partial-name-matches`, `POST …/partial-name-matches/{checkId}/reviewed`).

---

## 2. Tabs

Each person is in **exactly one** tab, the first that matches:

| `tab` | Who | Badge |
|---|---|---|
| `APPROVED` | Verified, by Smile ID or by ops | green "Verified" |
| `IN_REVIEW` | Waiting for ops: Smile ID sent it to a person (name mismatch, spoof or risk flag, NIMC has no photo…), or the older selfie upload | amber "In review" |
| `LOCKED_OUT` | Not verified and every try used (5 for NIN + selfie, 3 for a driver's NIN lookup). The app tells them to contact support. Never in the sandbox. | red "Locked out" |
| `FAILED` | Smile ID failed the last check and asked them to try again (`retryReason`), or ops rejected it | red "Failed" |
| `PENDING` | Started but not finished: Smile ID is still checking (`checkInProgress`), they closed the app mid-check, or (drivers) their NIN is confirmed and the licence is still outstanding | grey "Pending" |

### `GET /identity-verification/people/counts`

| Param | Default | Values |
|---|---|---|
| `search` | none | Same as the table |
| `role` | none = everyone | `RIDER` · `DRIVER` |

```json
{ "all": 7, "pending": 1, "inReview": 1, "failed": 2, "approved": 1, "lockedOut": 2 }
```

Send the same `search` and `role` as the table so the badges match what's listed.

---

## 3. Table: `GET /identity-verification/people`

| Param | Default | Values |
|---|---|---|
| `tab` | none = **All** | `PENDING` · `IN_REVIEW` · `FAILED` · `APPROVED` · `LOCKED_OUT` |
| `search` | none | Name (contains, any case), or a phone: 4+ digits, spaces and `+234`/`0` don't matter (`0803 111 0002`, `+2348031110002`, `1110002`) |
| `role` | none = everyone | `RIDER` (has a rider profile) · `DRIVER` (has a driver profile) |
| `sort` | `SUBMITTED_AT` | `SUBMITTED_AT` · `NAME` · `ATTEMPTS` · `DECIDED_AT` · `STATUS` (the tab) |
| `direction` | `ASC` | `ASC` · `DESC` |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

With no sort, the **oldest submission comes first**. Missing values (never decided, say) go last either way. An unknown `tab`, `role`, `sort` or `direction` is a `400`.

```json
{
  "content": [
    {
      "userId": "7c1e22aa-…", "ref": "R-7C1E22",
      "firstName": "Bola", "lastName": "Ige", "phone": "08031110002", "photoUrl": null,
      "rider": true, "driver": false,
      "tab": "FAILED", "status": "PENDING", "ninLast4": "1234",
      "submittedAt": "2026-10-01T08:01:00Z", "decidedAt": null,
      "attemptsUsed": 1, "attemptsLeft": 4,
      "retryReason": "FACE_MISMATCH", "checkInProgress": false,
      "latestOutcome": "DETAILS_MISMATCH",
      "latestSummary": "Selfie does not match the photo on the NIN record - Smile ID job job-9"
    }
  ],
  "page": 0, "size": 20, "totalElements": 7, "totalPages": 1, "last": true
}
```

| Column | From |
|---|---|
| Action | **View** opens `GET /identity-verification/people/{userId}` |
| ID | `ref`: `R-` for anyone with a rider profile, `D-` for a driver-only account |
| Name, phone under it | `firstName` `lastName`, `phone` (`null` if unknown: show a dash) |
| Role chips | `rider`, `driver` (both can be true) |
| NIN | `•••• ` + `ninLast4` |
| Submitted | `submittedAt`: when their latest check started |
| Status | `tab` (badge, section 2); for `FAILED` also show `retryReason` (section 9) |
| Tries | `attemptsUsed` / (`attemptsUsed` + `attemptsLeft`) |
| Smile ID said | `latestOutcome` badge; `latestSummary` as a tooltip |

`status` is the check's own status (`PENDING`, `IN_REVIEW`, `APPROVED`, `REJECTED`); use `tab` for the badge.

---

## 4. One person: `GET /identity-verification/people/{userId}`

```json
{
  "person": { "userId": "7c1e22aa-…", "ref": "R-7C1E22", "firstName": "Bola", "lastName": "Ige",
              "phone": "08031110002", "photoUrl": null, "rider": true, "driver": false },
  "tab": "FAILED",
  "status": "PENDING",
  "ninLast4": "1234",
  "submittedAt": "2026-10-01T08:01:00Z",
  "decidedAt": null,
  "decidedBy": null,
  "rejectionReason": null,
  "decisionNote": null,
  "providerOverridden": false,
  "verifiedRecord": null,
  "driverNinStatus": null,
  "attempts": {
    "checkType": "NIN", "used": 1, "max": 5, "left": 4, "lockedOut": false,
    "retryReason": "FACE_MISMATCH", "checkInProgress": false, "checkStartedAt": null, "legacyUploads": 0
  },
  "selfieUrl": null,
  "checks": [
    { "checkId": "5d3c…", "type": "NIN_FACE", "outcome": "DETAILS_MISMATCH", "nameMatchScore": null,
      "summary": "Selfie does not match the photo on the NIN record - Smile ID job job-9", "smileJobId": "job-9",
      "checkedAt": "2026-10-01T08:03:00Z", "partialNameMatchToReview": false, "reviewedAt": null, "hasImages": true }
  ],
  "actions": {
    "canApprove": true, "approveBlockedReason": null, "approveNeedsNote": true,
    "canReject": true, "rejectBlockedReason": null,
    "canResetAttempts": true, "resetBlockedReason": null
  },
  "history": []
}
```

| Field | Meaning |
|---|---|
| `decidedBy` | `SMILE_ID` (or `SMILE_ID_NIN_LOOKUP_AND_LICENCE`) for an automatic decision; otherwise the admin's user id. Show names from `history`. |
| `rejectionReason` | What the person was told, on a rejection |
| `decisionNote` | The note ops left with the last approval |
| `providerOverridden` | The approval went against Smile ID's result. Show an **"Approved by ops over Smile ID"** chip. |
| `verifiedRecord` | Name and date of birth on the NIMC record, kept when approved (null until then, and in the sandbox) |
| `driverNinStatus` | Drivers on the NIN lookup only: `PENDING`, `IN_REVIEW`, `APPROVED` or `REJECTED`. `null` for riders. |
| `attempts` | Tries on their current check. `checkType` is `NIN` (NIN + selfie) or `NIN_LOOKUP` (driver). `legacyUploads` counts the older selfie uploads. |
| `selfieUrl` | Only for the older selfie upload, while undecided |
| `checks` | Every Smile ID result, newest first. `partialNameMatchToReview: true` is the approval ops may still reject. `hasImages`: fetch with the images call. A `NIN_LOOKUP` never has images (no selfie). |
| `actions` | Drives the buttons. Each `…BlockedReason` is exactly the `400` message that action would get. `approveNeedsNote`: make the note box required. |
| `history` | Section 7 |

**Errors:** `404 This person hasn't started an identity check`.

### Images: `GET /identity-verification/people/{userId}/checks/{checkId}/images`

```json
{
  "checkId": "5d3c…", "smileJobId": "job-9",
  "images": [
    { "kind": "SELFIE", "name": "selfie_image", "url": "https://…" },
    { "kind": "ID_PHOTO", "name": "id_image", "url": "https://…" }
  ],
  "imagesUnavailableReason": null
}
```

- The links come fresh from Smile ID and **expire within hours**. Call this each time the image is opened; never store them.
- `kind`: `SELFIE`, `ID_PHOTO` (the NIN record's photo, when Smile ID returns it) or `OTHER`; listed in that order.
- No images: show `imagesUnavailableReason` and the `smileJobId`, so ops can open the job in the Smile ID portal.
- `404` if `checkId` isn't one of this person's identity checks.

---

## 5. Actions

### Approve: `POST /identity-verification/people/{userId}/approve`

```json
{ "note": "Selfie matched the NIN photo on a video call" }
```

- Body optional; `note` up to **500** characters.
- **`note` is required** when the person is in `FAILED` or `LOCKED_OUT` (the approval overrides Smile ID's result, or reverses a rejection). Follow `actions.approveNeedsNote`.
- From `IN_REVIEW` a note is optional; it is still logged as an override if Smile ID had flagged something (name mismatch, …).
- A driver on the **NIN lookup**: approving confirms their NIN (`driverNinStatus: APPROVED`). Their identity is verified at once if their licence is already approved; otherwise they move to `PENDING` until it is.

**Returns** the person. `status: "APPROVED"`, `providerOverridden` set if it overrode Smile ID.

| `400` message | When |
|---|---|
| `This person's identity is already verified` | Already approved |
| `This driver's NIN is already confirmed - their identity is verified once their licence is approved` | Driver whose NIN is confirmed, waiting for the licence |
| `Add a note saying why you're approving - it overrides Smile ID's result` | `FAILED` / `LOCKED_OUT` with no note |
| `Add a note saying why you're approving - it reverses the earlier rejection` | Rejected, no note |
| `note: size must be between 0 and 500` | Note too long |

### Reject: `POST /identity-verification/people/{userId}/reject`

```json
{ "reason": "The name on your NIN doesn't match your profile - update your profile name or contact support" }
```

- **`reason` is required**, up to **500** characters. The person sees it word for word.
- Rejecting a **partial name match** approval also marks it reviewed (it leaves the partial-name-match list).
- A driver on the NIN lookup: their NIN becomes `REJECTED` too.

**Returns** the person, `status: "REJECTED"`, `tab: "FAILED"`.

| `400` message | When |
|---|---|
| `reason: is required - the person sees it` | No reason, or only spaces (checked by the console API) |
| `Smile ID verified this identity, so it stays approved - only an approval on a partial name match that hasn't been reviewed yet can be rejected` | Rule 1 |
| `This identity check is already rejected` | Already rejected |

### Reset tries: `POST /identity-verification/people/{userId}/reset-attempts`

```json
{ "note": "Called in - their camera was broken" }
```

- Body optional; `note` up to 500 characters, kept in the audit log.
- Gives back every try: NIN + selfie and the driver's NIN lookup (and the older selfie uploads). The status doesn't change; a `LOCKED_OUT` person moves to `FAILED` (or `PENDING`) and can verify again from the app.

| `400` message | When |
|---|---|
| `This person's identity is already verified - there are no tries to give back` | Approved |
| `No tries have been used - there's nothing to reset` | Nothing used |

---

## 6. Common errors

| Status | When | What to show |
|---|---|---|
| `400` | A rule above, an unknown `tab` / `role` / `sort`, a note or reason too long | `message` as-is |
| `401` / `403` | Not signed in / not an ops admin | Send them to sign in |
| `404` | Unknown person, or a `checkId` that isn't theirs | `message` |
| `502` / `503` | A backing service is down | "Couldn't load, try again" |

---

## 7. Audit log: `GET /identity-verification/people/{userId}/history`

Newest first. The same entries come inside the person as `history`. A driver's identity entries also appear in their driver audit log (`GET /drivers/{driverId}/verification/history`).

```json
[
  {
    "subject": "IDENTITY", "item": null, "label": "Identity (NIN)",
    "action": "OVERRIDE_APPROVED", "previousStatus": "PENDING", "status": "APPROVED",
    "overrodeProvider": true, "providerOutcome": "FACE_MISMATCH",
    "note": "Selfie matched the NIN photo on a video call",
    "decidedBy": "Ada Ops", "decidedById": "0b5e…", "decidedAt": "2026-10-03T09:00:00Z"
  }
]
```

| Field | Meaning |
|---|---|
| `action` | `APPROVED` · `OVERRIDE_APPROVED` (approved over Smile ID's result) · `REJECTED` · `ATTEMPTS_RESET` |
| `previousStatus` → `status` | The check's status before and after. Unchanged for `ATTEMPTS_RESET`. |
| `providerOutcome` | What Smile ID last said: a retry reason (`FACE_MISMATCH`, `NIN_NOT_FOUND`, `UNREADABLE`, `TRY_LATER`) or an outcome (`NAME_MISMATCH`, `DETAILS_MISMATCH`, `NOT_FOUND`, `PROVIDER_ERROR`) |
| `note` | The approval note, the rejection reason, or the reset note |
| `decidedBy` / `decidedById` | The admin's name / user id |

---

## 8. What the mobile app sees

The app reads the person's own check from `GET /profile/identity-verification/me` (riders, and drivers on the NIN + selfie check) or `GET /profile/drivers/me` (drivers on the NIN lookup: `ninStatus`, `ninRetryReason`, `ninTriesLeft`). Each ops action also sends a push.

| Ops action | Push (title / body) | What the app reads afterwards |
|---|---|---|
| **Approve** | "Identity verified" / "Your identity has been verified. You're all set." | `status: "APPROVED"`. Booking (rider) or posting (driver) unlocks. Their profile name becomes read-only. |
| **Approve** (driver on the NIN lookup, licence still outstanding) | "NIN confirmed" / "Your NIN has been confirmed. Finish verifying your licence to complete your identity check." | `/drivers/me` → `ninStatus: "MATCH"`; identity completes when the licence is approved |
| **Reject** | "Identity check needs attention" / the `reason` | `status: "REJECTED"`, `rejectionReason` = the reason. The app shows it with a **Try again** button; a new check is allowed while `attemptsLeft > 0`. |
| **Reset tries** | "You can verify your identity again" / "We've reset your identity check. Open the app to try again." | `attemptsLeft` back to 5 (`ninTriesLeft` back to 3); the "contact support" message goes away |

Push `type`s: `IDENTITY_VERIFICATION_APPROVED`, `IDENTITY_VERIFICATION_REJECTED`, `IDENTITY_VERIFICATION_RETRY`. Smile ID's own results send no push, as before.

---

## 9. Values

**`retryReason`** (why Smile ID asked them to try again)

| Value | Meaning |
|---|---|
| `FACE_MISMATCH` | The selfie isn't the face on the NIN record |
| `NIN_NOT_FOUND` | NIMC has no record of this NIN (usually a typo) |
| `UNREADABLE` | The selfie couldn't be used (blurry, dark, glare). No try used. |
| `TRY_LATER` | Smile ID or NIMC was down. No try used. |

**`latestOutcome` / `checks[].outcome`**: `MATCH`, `PARTIAL_NAME_MATCH`, `NAME_MISMATCH`, `DETAILS_MISMATCH`, `NOT_FOUND`, `PROVIDER_ERROR`; see [verification-queue.md](verification-queue.md) section 9.

**`checks[].type`**: `NIN_FACE` (NIN + live selfie) or `NIN_LOOKUP` (driver's NIN by number; also the NIN-vs-licence cross-check).

---

## 10. Checklist

- [ ] Tabs with counts (`/people/counts`), same `search` and `role` as the table.
- [ ] Table: search, role filter, column sorting, page size and paging; badge from `tab`, retry reason under `FAILED`.
- [ ] Person: details, tries, every Smile ID result with images loaded on demand (never stored), "Approved by ops over Smile ID" chip from `providerOverridden`.
- [ ] Buttons follow `actions`; note box required when `approveNeedsNote`; reject always asks for a reason.
- [ ] Refresh the person from each action's response; show every `400` `message` as-is.
- [ ] Audit log from `history`.

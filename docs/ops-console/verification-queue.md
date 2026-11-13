# Ops Console: Verification Queue

Sidebar: **Drivers → Verification Queue**.

Everything the **Drivers → Verification Queue** screens need:
- the queue table,
- the review summary,
- each document's screen,
- **Verify / Flag / Reset to Default**,
- **Approve Driver / Reject**,
- the audit log,
- licences still waiting for Smile ID,
- **per-document actions**: approve one document on its own (including a licence Smile ID failed), and **Request resubmission** of one document with a reason,
- what the driver app sees after each action (section 13).

**The rules (enforced by the server):**
1. **A licence Smile ID approved stays approved.** It can't be flagged, rejected or sent back; you get a `400`. The one exception: a licence approved on a **partial name match** that ops hasn't reviewed yet. A licence **ops** approved can still be flagged or sent back.
2. **Ops can approve a licence Smile ID failed** (it asked the driver to try again) or sent for review, and any document that's waiting. Approving over Smile ID's failed check, or over a rejection, needs a **note**. Every approval over a Smile ID result is recorded as an **override** in the audit log: who, when, the note, and what Smile ID said.
3. **A rejection or resubmission request always has a reason** the driver sees.
4. **Every decision is in the audit log**, with the status before and after (section 8).

The driver's **NIN** (the NIN lookup) isn't one of the four documents. Decide it on the identity screen: see [rider-identity.md](rider-identity.md) (`role=DRIVER`).

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back with `success: false` and a `message` you can show as-is.
- **Who's recorded:** the reviewer on every action is the signed-in admin. You never send it.

---

## 0. What a driver submits

A driver goes live once all four items are approved:

| `item` | `label` (show this) | What ops checks | Automatic check |
|---|---|---|---|
| `GOVERNMENT_ID` | Driver's licence | Licence images, name, number, date of birth, expiry | **Smile ID** (Document Verification + live selfie) |
| `VEHICLE` | Vehicle registration | Make, model, year, colour, plate, exterior and interior photos, AC | none |
| `INSURANCE` | Insurance certificate | Certificate file, expiry date | none |
| `ROADWORTHINESS_CERT` | Roadworthiness certificate | Certificate file, expiry date | none |

**The licence and Smile ID:**
- A licence that Smile ID finds genuine, in the driver's name, with their number and their face, is approved automatically and never reaches this queue.
- Anything doubtful lands here: a name, number or expiry mismatch, or a forgery, photocopy, spoof or risk flag.
- A licence can also be **still waiting for Smile ID**. Ops can decide it anyway; see section 7.

**Not in this queue:** National ID, Live Selfie and Car Ownership. The NIN + live selfie check is a separate Smile ID check with its own identity queue. Insurance replaced Car Ownership.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Tabs **All / Pending / In Review / Resubmitted** and their counts | `GET /drivers/verification/queue/counts` |
| Queue table: search, sorting, paging ("Show 10 entries", "Showing 1–10 of N") | `GET /drivers/verification/queue` |
| **View**: the review summary and the "Submitted Document" list with ✓ / ✗ | `GET /drivers/{driverId}/verification` |
| Driver's Licence screen | the review page, plus `GET /drivers/{driverId}/verification/licence` |
| Vehicle Registration, Insurance Certificate and Roadworthiness screens | the review page |
| **Verify** / **Flag** on a document | `PUT /drivers/{driverId}/verification/items/{item}/review` |
| **Reset to Default** | `DELETE /drivers/{driverId}/verification/items/{item}/review` |
| **Approve Driver** | `POST /drivers/{driverId}/verification/approve` |
| **Reject** | `POST /drivers/{driverId}/verification/reject` |
| **Save Changes** (used by the [Drivers Directory](drivers-directory.md)) | `POST /drivers/{driverId}/verification/save` |
| **Approve** one document (incl. over Smile ID) | `POST /drivers/{driverId}/verification/items/{item}/approve` |
| **Request resubmission** of one document | `POST /drivers/{driverId}/verification/items/{item}/request-resubmission` |
| Audit log | `GET /drivers/{driverId}/verification/history` |

**Every action returns the whole review page**, so the screen refreshes from the response with no second call. The actions are mark, reset, approve, reject, save, approve one document and request resubmission.

---

## 2. The flow

1. **Queue.** Ops picks a tab, searches or sorts, then taps **View** on a row.
2. **Review summary.** It shows name, phone, submitted time, time in queue and status, then the four documents, each with its mark:
   - ✓ Verified,
   - ✗ Flagged,
   - nothing for Unreviewed.
3. **A document screen.** Ops looks at the file or images, then:
   - **Verify**, which marks it ✓; or
   - **Flag**, which needs a note (the reason the driver will see) and marks it ✗.

   **Reset to Default** clears the mark.
4. **Back on the summary:**
   - **Approve Driver** is enabled once every document is Verified or already approved, and none is flagged.
   - **Reject** is enabled once at least one document is flagged.
5. **After a decision:**
   - The driver gets a push for each document.
   - Rejected documents show the flag's note as the reason in the driver app.
   - The driver re-uploads and comes back under **Resubmitted**.
   - Once all four documents are approved, the driver can post trips.

Marks are **saved on the server** as they're made. Closing the screen loses nothing, and a second reviewer sees the same marks.

---

## 3. Queue

### `GET /drivers/verification/queue`

| Param | Default | Values |
|---|---|---|
| `tab` | none = **All** | `PENDING` · `IN_REVIEW` · `RESUBMITTED`. Leave it out for All. |
| `search` | none | Driver name, or the driver ref (`D-4F2A91`; the `D-` is optional) |
| `sort` | `WAITING_SINCE` | `WAITING_SINCE` (In queue) · `SUBMITTED_AT` · `NAME` · `DRIVER_REF` |
| `direction` | `ASC` | `ASC` · `DESC` |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

With no sort, the longest-waiting driver comes first. An unknown `tab` or `sort` gets a `400`.

```json
{
  "content": [
    {
      "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "tab": "RESUBMITTED",
      "waitingSince": "2026-10-03T08:00:00Z",
      "submittedAt": "2026-10-02T17:10:00Z",
      "items": [
        { "item": "GOVERNMENT_ID", "label": "Driver's licence", "status": "APPROVED",
          "submittedAt": "2026-10-02T17:10:00Z", "resubmitted": false, "awaitingProvider": false },
        { "item": "VEHICLE", "label": "Vehicle registration", "status": "IN_REVIEW",
          "submittedAt": "2026-10-03T08:00:00Z", "resubmitted": true, "awaitingProvider": false },
        { "item": "INSURANCE", "label": "Insurance certificate", "status": "IN_REVIEW",
          "submittedAt": "2026-10-02T17:12:00Z", "resubmitted": false, "awaitingProvider": false },
        { "item": "ROADWORTHINESS_CERT", "label": "Roadworthiness certificate", "status": "PENDING",
          "submittedAt": null, "resubmitted": false, "awaitingProvider": false }
      ]
    }
  ],
  "page": 0, "size": 20, "totalElements": 9, "totalPages": 1, "last": true
}
```

| Column | From |
|---|---|
| Action | **View** opens the review page for `driver.id` |
| Driver ID | `driver.ref` |
| Name, and the phone under it | `driver.name`, `driver.phone`. The phone is `null` if it couldn't be loaded; show a dash. |
| Submitted date & time | `submittedAt`: the earliest submission among the items |
| Documents | One chip per entry in `items`, using `label`. Grey out `PENDING` chips that aren't `awaitingProvider`, since they haven't been uploaded. A chip with `awaitingProvider: true` reads **"Awaiting Smile ID"**. |
| In queue | Time since `waitingSince`: when the oldest still-waiting item came in |
| Footer | `totalElements`, `page`, `size` |

**Which tab a driver is under:** each driver is in exactly one tab, the first that matches.
1. **Resubmitted:** they re-uploaded something ops had rejected.
2. **In Review:** some items are decided and others are still waiting.
3. **Pending:** nothing has been decided yet.

### `GET /drivers/verification/queue/counts?search=`

```json
{ "all": 9, "pending": 4, "inReview": 3, "resubmitted": 2 }
```

Send the same `search` as the list, so the badges match what's listed.

---

## 4. Review page: `GET /drivers/{driverId}/verification`

```json
{
  "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "firstName": "Emeka", "lastName": "Okafor",
              "phone": "08053636321", "photoUrl": "…", "licenceNumber": "LAG12345678", "dateOfBirth": "1990-01-01" },
  "submittedAt": "2026-10-02T17:10:00Z",
  "waitingSince": "2026-10-03T08:00:00Z",
  "overallStatus": "IN_REVIEW",
  "verifiedSince": null,

  "governmentId": {
    "status": "IN_REVIEW", "rejectionReason": null, "submittedAt": "2026-10-02T17:10:00Z",
    "resubmitted": false, "awaitingProvider": false,
    "url": null, "file": null
  },

  "vehicleRegistration": {
    "status": "IN_REVIEW", "rejectionReason": null, "submittedAt": "2026-10-03T08:00:00Z", "resubmitted": true,
    "vehicle": {
      "make": "Toyota", "model": "Corolla", "year": 2025, "color": "Black", "plateNumber": "AGL-354-RF",
      "hasAC": true, "acConfirmedWorking": true,
      "exteriorPhotoUrl": "…", "interiorPhotoUrl": "…",
      "exteriorPhoto": { "url": "…", "downloadUrl": "…", "fileType": "IMAGE" },
      "interiorPhoto": { "url": "…", "downloadUrl": "…", "fileType": "IMAGE" }
    }
  },
  "insuranceExpiresOn": "2026-12-09",

  "documents": [
    { "type": "INSURANCE", "status": "IN_REVIEW", "rejectionReason": null, "expiresOn": "2026-12-09",
      "submittedAt": "2026-10-02T17:12:00Z", "resubmitted": false,
      "documentUrl": "…", "file": { "url": "…", "downloadUrl": "…", "fileType": "PDF" } },
    { "type": "ROADWORTHINESS_CERT", "status": "IN_REVIEW", "rejectionReason": null, "expiresOn": "2027-03-01",
      "submittedAt": "2026-10-02T17:13:00Z", "resubmitted": false,
      "documentUrl": "…", "file": { "url": "…", "downloadUrl": "…", "fileType": "IMAGE" } }
  ],

  "providerChecks": [
    { "type": "DRIVERS_LICENCE", "outcome": "NAME_MISMATCH", "nameMatchScore": 0.62,
      "summary": "Licence is in the name of EMEKA OKAFOR-BELLO, profile says Emeka Okafor - Smile ID job job-9",
      "checkedAt": "…", "providerReference": "job-9" }
  ],

  "resubmissionRequests": [],

  "review": {
    "items": [
      { "item": "GOVERNMENT_ID", "label": "Driver's licence", "status": "IN_REVIEW", "submittedAt": "…", "resubmitted": false,
        "awaitingProvider": false, "mark": "VERIFIED", "note": null, "markedBy": "Ada Ops", "markedAt": "…",
        "canApproveItem": true, "approveItemBlockedReason": null, "approveItemNeedsNote": false,
        "canRequestResubmission": true, "resubmissionBlockedReason": null,
        "providerOutcome": "NAME_MISMATCH", "lockedBySmileId": false },
      { "item": "VEHICLE", "label": "Vehicle registration", "status": "IN_REVIEW", "submittedAt": "…", "resubmitted": true,
        "awaitingProvider": false, "mark": "FLAGGED", "note": "Blurry document", "markedBy": "Ada Ops", "markedAt": "…",
        "canApproveItem": true, "approveItemBlockedReason": null, "approveItemNeedsNote": false,
        "canRequestResubmission": true, "resubmissionBlockedReason": null, "providerOutcome": null, "lockedBySmileId": false },
      { "item": "INSURANCE", "label": "Insurance certificate", "status": "IN_REVIEW", "submittedAt": "…", "resubmitted": false,
        "awaitingProvider": false, "mark": null, "note": null, "markedBy": null, "markedAt": null,
        "canApproveItem": true, "approveItemBlockedReason": null, "approveItemNeedsNote": false,
        "canRequestResubmission": true, "resubmissionBlockedReason": null, "providerOutcome": null, "lockedBySmileId": false },
      { "item": "ROADWORTHINESS_CERT", "label": "Roadworthiness certificate", "status": "IN_REVIEW", "submittedAt": "…",
        "resubmitted": false, "awaitingProvider": false, "mark": null, "note": null, "markedBy": null, "markedAt": null,
        "canApproveItem": true, "approveItemBlockedReason": null, "approveItemNeedsNote": false,
        "canRequestResubmission": true, "resubmissionBlockedReason": null, "providerOutcome": null, "lockedBySmileId": false }
    ],
    "canApprove": false,
    "approveBlockedReason": "Flagged: Vehicle registration - reject, or reset the flag",
    "canReject": true,
    "canSave": true
  }
}
```

### Review summary box

| Shown | From |
|---|---|
| Header "Emeka Okafor (D-4F2A91)" | `driver.firstName` + `driver.lastName`, `driver.ref` |
| Name, Phone | `driver.*` |
| Submitted | `submittedAt` |
| In queue for | Time since `waitingSince` |
| Status badge | `overallStatus`; see section 9 for the labels |

### "Submitted Document" list
- Use `review.items`, which comes in this order: licence, vehicle, insurance, roadworthiness. Show `label` for each.
- The icon next to each is ✓ for `VERIFIED`, ✗ for `FLAGGED`, and nothing for `null` (Unreviewed).
- An item that's already **approved** or **rejected** (`status`) has no mark. Show its decided state instead.
- **Approve Driver** is enabled when `review.canApprove`. When it's disabled, show `review.approveBlockedReason` near the button.
- **Reject** is enabled when `review.canReject`.
- `review.canSave` says whether **Save Changes** would change anything. It's used on the Drivers Directory's Documents list.
- If `review` is missing, the marks couldn't be loaded. The rest of the page still works; offer a retry.

### Per-document buttons (each `review.items[]`)

| Field | Use |
|---|---|
| `canApproveItem` / `approveItemBlockedReason` | Enable the document's own **Approve** button; when disabled, show the reason (it's the exact `400` message) |
| `approveItemNeedsNote` | Approving goes against Smile ID's failed check or a rejection: make the note box required |
| `canRequestResubmission` / `resubmissionBlockedReason` | Enable **Request resubmission** |
| `providerOutcome` | Licence only: what Smile ID last said when it didn't approve it - a retry it asked for (`FACE_MISMATCH`, `NOT_A_LICENCE`, `UNREADABLE`, `TRY_LATER`) or a finding (`NAME_MISMATCH`, `DETAILS_MISMATCH`, …). `null` when Smile ID approved it, never checked it, or is checking now. Show it as "Smile ID: …" |
| `lockedBySmileId` | Licence only: Smile ID approved it, so it stays approved. Hide **Flag** and **Request resubmission**; show "Verified by Smile ID". |

### `resubmissionRequests`
Documents ops asked the driver to upload again and that haven't been re-uploaded yet. Show a "Waiting for the driver to re-upload" banner on each.

```json
"resubmissionRequests": [
  { "item": "INSURANCE", "label": "Insurance certificate",
    "reason": "The policy number is cut off - upload the full page", "requestedAt": "2026-10-04T09:00:00Z" }
]
```

### Files (every document)
Each file comes as `{ url, downloadUrl, fileType }`:
- **Preview:** `url`.
- **PDF / image badge:** `fileType` is `PDF` or `IMAGE`.
- **Download button:** `downloadUrl`.
- `file` is `null` only when nothing was uploaded.
- **Files are kept once approved**, so ops can always look at a driver's documents.
- When the driver uploads a replacement, the old file is removed.

---

## 5. Document screens

All four screens share:
- the header "Document name / Driver name (ref)",
- the back and close buttons,
- a status badge: **Unreviewed / Verified / Flagged** from `review.items[].mark`,
- a review note box,
- **Flag** and **Verify**, or **Reset to Default** once marked.

### Driver's Licence
**Licence details:**
- **Number and date of birth:** `driver.licenceNumber` and `driver.dateOfBirth` on the review page.
- **What Smile ID found:** the latest `providerChecks` entry with `type: DRIVERS_LICENCE`. Show its `outcome` badge and `summary`.

**Images: `GET /drivers/{driverId}/verification/licence`**

```json
{
  "smileJobId": "job-9", "checkedAt": "…", "outcome": "NAME_MISMATCH",
  "summary": "Licence is in the name of … - Smile ID job job-9",
  "licence": { "fullName": "EMEKA OKAFOR", "licenceNumber": "LAG12345678",
               "dateOfBirth": "1990-01-01", "expirationDate": "2029-05-01" },
  "images": [
    { "kind": "LICENCE_FRONT", "name": "id_card_image", "url": "https://…" },
    { "kind": "LICENCE_BACK",  "name": "id_card_back",  "url": "https://…" },
    { "kind": "SELFIE",        "name": "selfie_image",  "url": "https://…" }
  ],
  "imagesUnavailableReason": null
}
```

- `licence` is what Smile ID read off the card. Show it next to what the driver entered.
- `images` come sorted front, back, selfie.
- **The links are fetched fresh from Smile ID and expire within hours.** Call this endpoint each time the screen opens, and never store the links.
- If `images` is empty, show `imagesUnavailableReason` and the Smile ID job (`smileJobId`) so ops can open it in the Smile ID portal.
- `404` means there's no licence check yet. If the driver used the older photo upload instead, use `governmentId.file`.
- The button label is **Verify Licence**.

### Vehicle Registration
| Shown | From |
|---|---|
| Make, Model, Year, Color, Plate Number | `vehicleRegistration.vehicle.*` |
| Insurance Expiry date | `insuranceExpiresOn` |
| Exterior Photo / Interior Photo | `vehicle.exteriorPhoto` / `vehicle.interiorPhoto`: their `url`, `downloadUrl` and `fileType` |
| Air Conditioning "Active" | `vehicle.hasAC && vehicle.acConfirmedWorking` |

### Insurance Certificate
- From the entry in `documents` with `type: INSURANCE`: `file` and `expiresOn`.
- The button label is **Approve Insurance**.

### Roadworthiness
- From the entry in `documents` with `type: ROADWORTHINESS_CERT`: `file` and `expiresOn`.

**A document that isn't in `documents`** hasn't been uploaded yet.

---

## 6. Actions

`{item}` is one of `GOVERNMENT_ID` · `VEHICLE` · `INSURANCE` · `ROADWORTHINESS_CERT`.

### Verify / Flag: `PUT /drivers/{driverId}/verification/items/{item}/review`

```json
{ "mark": "VERIFIED" }
{ "mark": "VERIFIED", "note": "Matches the insurer's records" }
{ "mark": "FLAGGED",  "note": "Blurry document" }
```

**Rules:**
- **A flag needs a note.** It becomes the rejection reason the driver sees, so it's at most **255** characters.
- A note on Verified is optional (up to 500 characters) and goes into the audit log.
- These items can be marked:
  - anything **waiting for ops**: `status: IN_REVIEW`, or `awaitingProvider: true`;
  - a document that's already **approved** (or `EXPIRED`). Flagging one and pressing **Reject** or **Save Changes** takes the approval back; the driver must re-upload it.
- A `REJECTED` item (waiting for the driver) and a `PENDING` one (never uploaded) can't be marked.
- A licence **Smile ID approved** can't be flagged (`lockedBySmileId`): `400 Smile ID verified this driver's licence, so it stays approved - only a licence approved on a partial name match that ops hasn't reviewed yet can be rejected or sent back`. Verifying it is fine.
- Marking again replaces the old mark.
- If the driver re-uploads, the old mark stops counting and the item shows as Unreviewed.

**Returns** the review page.

### Reset to Default: `DELETE /drivers/{driverId}/verification/items/{item}/review`
Back to Unreviewed. Resetting an item with no mark is fine. **Returns** the review page.

### Approve Driver: `POST /drivers/{driverId}/verification/approve`

```json
{ "note": "All clear" }
```

**Rules:**
- The body is optional.
- The note is saved with each approval in the audit log, unless that document has its own note from Verify.
- **Allowed only when** every item is either already approved, or waiting for ops **and** marked Verified, and nothing is flagged.

**Result:**
- All waiting items are approved.
- The marks are cleared.
- The driver gets a push for each document.
- With all four approved, the driver can post.

**Returns** the review page with `overallStatus: "APPROVED"`.

**Errors** (`400`, the same text as `approveBlockedReason`), checked in this order:

| Message | Meaning |
|---|---|
| `Not uploaded yet: Vehicle registration, …` | The driver hasn't submitted it |
| `Waiting for the driver to re-upload: Insurance certificate` | It was rejected, and the driver hasn't sent a new one |
| `Flagged: Vehicle registration - reject, or reset the flag` | Something is flagged |
| `Not reviewed yet: Roadworthiness certificate` | A waiting item has no mark |
| `Nothing is waiting for review` | Everything is already decided |

### Reject: `POST /drivers/{driverId}/verification/reject`
No body. It needs **at least one flagged** document.

| Mark | Becomes |
|---|---|
| Flagged | **Rejected.** The note is the reason shown to the driver. |
| Verified | **Approved**, at the same time |
| Unmarked | Unchanged: still waiting |

The driver gets a push per document, re-uploads, and returns under **Resubmitted**. **Returns** the review page.

**Error:** `400 Flag at least one document, with the reason, to reject`.

### Save Changes: `POST /drivers/{driverId}/verification/save`
This is the [Drivers Directory](drivers-directory.md)'s button. It applies the marks as they stand, with none of Approve's all-or-nothing rules. No body.

| Mark | Becomes |
|---|---|
| Flagged | **Rejected**, with the note as the reason. An approved document loses its approval. |
| Verified, on a document waiting for ops | **Approved** |
| Verified, on a document already approved | Unchanged |
| Unmarked | Unchanged |

Every mark is cleared afterwards. **Returns** the review page.

**Error:** `400 Nothing to save - verify or flag a document first`. `review.canSave` tells you in advance.

### Approve one document: `POST /drivers/{driverId}/verification/items/{item}/approve`

```json
{ "note": "Selfie was in poor light - matched the licence on a video call" }
```

Approves just this document, now, without the all-or-nothing rule of **Approve Driver**. It's how ops approves **a licence Smile ID failed**.

- Body optional; `note` up to **500** characters, kept in the audit log.
- **Allowed** for: a document in review; a licence still with Smile ID (`awaitingProvider`, see section 7); a **licence Smile ID failed** (`status: PENDING`, `providerOutcome` set); a `REJECTED` document (ops changing its mind).
- **`note` is required** when `approveItemNeedsNote`: the licence Smile ID failed, or a rejected document.
- Any mark on the document is cleared. The driver gets a push; with all four approved, they can post.
- The audit log entry is `OVERRIDE_APPROVED` with `providerOutcome` when Smile ID hadn't approved the licence; `APPROVED` otherwise.

**Returns** the review page.

| `400` message | When |
|---|---|
| `The vehicle registration is already approved` | Already approved |
| `The insurance certificate has expired - the driver must upload a current one` | `EXPIRED` |
| `The driver's licence hasn't been uploaded yet` | `PENDING`, never uploaded, and Smile ID never answered |
| `Add a note saying why you're approving the driver's licence - it goes against Smile ID's result` | Licence Smile ID failed, no note |
| `Add a note saying why you're approving the insurance certificate - it goes against its rejection` | Rejected document, no note |
| `note: size must be between 0 and 500` | Note too long |

An unknown `{item}` is a `400`.

### Request resubmission: `POST /drivers/{driverId}/verification/items/{item}/request-resubmission`

```json
{ "reason": "The policy number is cut off - upload the full page" }
```

Asks the driver to upload this one document again. It becomes **`REJECTED`** at once with `reason` as its `rejectionReason`, and appears in `resubmissionRequests`. When the driver re-uploads, it goes back to **`IN_REVIEW`** (marked `resubmitted`, under the **Resubmitted** tab) and leaves `resubmissionRequests`.

- **`reason` is required**, up to **255** characters. The driver sees it word for word.
- **Allowed** for a document in review, approved (the approval is taken back, and the driver can't post until the new one is approved), expired, or a licence still with Smile ID.
- Any mark on the document is cleared.

**Returns** the review page.

| `400` message | When |
|---|---|
| `reason: is required - the person sees it` | No reason (checked by the console API) |
| `Keep the reason to 255 characters - the driver sees it` | Too long |
| `The insurance certificate is already waiting for the driver to upload it again` | Already `REJECTED` |
| `The insurance certificate hasn't been uploaded yet - there's nothing to send back` | Never uploaded |
| `Smile ID verified this driver's licence, so it stays approved - only a licence approved on a partial name match that ops hasn't reviewed yet can be rejected or sent back` | Rule 1 (`lockedBySmileId`) |

### Older: `POST /drivers/{driverId}/verification/decisions`
The original all-in-one call still works, if you'd rather keep marks in the page:

```json
{ "decisions": [
    { "item": "GOVERNMENT_ID", "status": "APPROVED", "note": "optional, for the audit log" },
    { "item": "VEHICLE", "status": "REJECTED", "rejectionReason": "Plate in the photo doesn't match" } ] }
```

- Each rejection needs its own `rejectionReason` (up to 255 characters).
- Rejecting a licence Smile ID approved is refused, as for a flag (rule 1).
- An item appears at most once per call.
- If any rule fails, nothing is saved.

**Returns** the review page.

---

## 7. A licence still with Smile ID

Smile ID usually answers within minutes, but some results take hours. Ops can decide in the meantime.

**How it appears:**
- The licence has `status: "PENDING"` with **`awaitingProvider: true`**. This is on the queue row's `items`, on `governmentId`, and on `review.items`. Show **"Awaiting Smile ID"**.
- `submittedAt` is when the check started.
- The driver joins the queue after **10 minutes** of waiting. Ops can change this with `VERIFICATION_PROVIDER_PENDING_QUEUE_AFTER_MINUTES`.

**What ops can do:**
- Verify or Flag it, then **Approve Driver** or **Reject**, like any other document.
- There's **no image yet**, because Smile ID's job only exists once its result is in. The licence screen's call returns `404` or no images; decide from what the driver sent through support.
- A driver who opened the licence check and quit also shows as awaiting, because that can't be told apart until a result arrives.

**Smile ID's result overrides the ops decision when it arrives:**

| Smile ID says | Licence becomes |
|---|---|
| Genuine, details match | **Approved**, even if ops rejected it |
| Needs a human look (name or number mismatch, forgery or risk flag…) | **In review** again, with Smile ID's finding in `providerChecks` |
| The driver must try again (face mismatch, wrong document…) | **Pending**, waiting for the driver's next check |

**After an override:**
- It's recorded in the audit log with `decidedBy: "SMILE_ID"` and a note.
- Whether the driver can post follows the override automatically.

**When there's no override:**
- If ops decides **after** Smile ID has already answered, a later result can't override it.
- If the driver starts a new check, the old result has nothing left to override.

### A licence Smile ID failed

When Smile ID asks the driver to try again (the selfie isn't the face on the licence, it isn't a licence it accepts, the photo was unusable…), the licence goes back to `status: "PENDING"` with `awaitingProvider: false`, and `review.items[].providerOutcome` says why (`FACE_MISMATCH`, `NOT_A_LICENCE`, `UNREADABLE`, `TRY_LATER`). The driver is asked to retry in the app; after 5 failed tries the app tells them to contact support.

Ops can approve it anyway with **Approve** on the document (section 6), **with a note**. It's recorded as `OVERRIDE_APPROVED` with Smile ID's `providerOutcome`. Since Smile ID has already answered, nothing overrides this approval later.

A licence Smile ID sent to ops (`IN_REVIEW`: name, number or expiry mismatch, forgery or risk flag) is approved the usual way, by **Verify** + **Approve Driver** or by **Approve** on the document; no note is required, and the audit log still records it as `OVERRIDE_APPROVED` with Smile ID's finding.

---

## 8. Audit log: `GET /drivers/{driverId}/verification/history`

Newest first. It includes every decision:
- from the review screen and the older `decisions` call: `decidedBy` is the admin's name;
- Smile ID's automatic licence approvals and overrides: `decidedBy` is `"SMILE_ID"`.

It also includes the driver's **identity (NIN)** decisions made on the identity screen: `subject: "IDENTITY"`, `item: null`, `label: "Identity (NIN)"`.

```json
[
  { "item": "INSURANCE", "subject": "INSURANCE", "label": "Insurance certificate",
    "action": "RESUBMISSION_REQUESTED", "previousStatus": "APPROVED", "status": "REJECTED",
    "overrodeProvider": false, "providerOutcome": null,
    "note": "The policy number is cut off - upload the full page",
    "decidedBy": "Ada Ops", "decidedById": "0b5e…", "decidedAt": "2026-10-04T09:00:00Z" },
  { "item": "GOVERNMENT_ID", "subject": "GOVERNMENT_ID", "label": "Driver's licence",
    "action": "OVERRIDE_APPROVED", "previousStatus": "PENDING", "status": "APPROVED",
    "overrodeProvider": true, "providerOutcome": "FACE_MISMATCH",
    "note": "Selfie was in poor light - matched the licence on a video call",
    "decidedBy": "Ada Ops", "decidedById": "0b5e…", "decidedAt": "2026-10-03T10:00:00Z" },
  { "item": "GOVERNMENT_ID", "subject": "GOVERNMENT_ID", "label": "Driver's licence",
    "action": "PROVIDER_RESULT", "previousStatus": null, "status": "IN_REVIEW", "overrodeProvider": false, "providerOutcome": null,
    "note": "Smile ID's result came in after ops decided - back for review: …", "decidedBy": "SMILE_ID", "decidedById": null, "decidedAt": "…" },
  { "item": "VEHICLE", "subject": "VEHICLE", "label": "Vehicle registration",
    "action": "REJECTED", "previousStatus": "IN_REVIEW", "status": "REJECTED", "overrodeProvider": false, "providerOutcome": null,
    "note": "Blurry document", "decidedBy": "Ada Ops", "decidedById": "0b5e…", "decidedAt": "2026-10-03T09:00:00Z" }
]
```

| Field | Meaning |
|---|---|
| `action` | `APPROVED` · `OVERRIDE_APPROVED` (approved over Smile ID's result) · `REJECTED` · `RESUBMISSION_REQUESTED` · `PROVIDER_RESULT` (Smile ID's late result over an ops decision) · `ATTEMPTS_RESET` (identity only). `null` on entries from before these were recorded: read `status` then. |
| `previousStatus` → `status` | The item's status before and after. `previousStatus` is `null` on older entries. |
| `overrodeProvider` / `providerOutcome` | The approval went against Smile ID; `providerOutcome` is what it had said |
| `note` | The rejection or resubmission reason the driver saw, or the note left with an approval |
| `decidedBy` / `decidedById` | The admin's name / user id; `SMILE_ID` (no id) for Smile ID |

---

## 9. Values and badges

**Item `status`**

| Value | Meaning | Badge |
|---|---|---|
| `PENDING` | Not uploaded. With `awaitingProvider: true`, it's waiting for Smile ID instead. | grey: "Not uploaded" / "Awaiting Smile ID" |
| `IN_REVIEW` | Waiting for ops | amber: "In review" |
| `APPROVED` | Approved | green |
| `REJECTED` | Rejected; the driver must re-upload | red |
| `EXPIRED` | Insurance or roadworthiness passed its expiry date; the driver must upload a new one. The car can't be used to post trips until then. | red: "Expired" |

**Review `mark`**

| Value | Shown as |
|---|---|
| `null` | **Unreviewed** |
| `VERIFIED` | **Verified** ✓ |
| `FLAGGED` | **Flagged** ✗, with its note |

**`overallStatus`**

| Value | Shown as |
|---|---|
| `PENDING` | Not submitted |
| `IN_REVIEW` | In review |
| `APPROVED` | Verified |
| `REJECTED` | Rejected |

**Queue `tab`**: `PENDING`, `IN_REVIEW` or `RESUBMITTED`; see section 3.

**`providerChecks[].outcome`.** These are advisory; ops always makes the final call.

| Value | Meaning | Badge |
|---|---|---|
| `MATCH` | Genuine; details agree | green |
| `PARTIAL_NAME_MATCH` | Approved on a partial name match; worth a look | amber |
| `NAME_MISMATCH` | The name on the licence differs from the profile | amber |
| `DETAILS_MISMATCH` | Number or expiry differs, or the licence has expired (older drivers: plate details) | amber |
| `NOT_FOUND` | Not a licence, or no record | red |
| `PROVIDER_ERROR` | Smile ID couldn't finish; not the driver's fault | grey: "Check unavailable" |

**`file.fileType`**: `IMAGE` or `PDF`.

**Licence image `kind`**: `LICENCE_FRONT`, `LICENCE_BACK`, `SELFIE` or `OTHER`.

---

## 10. Errors

| Status | When | What to show |
|---|---|---|
| `400` | A rule isn't met: a flag with no note, approving with something unreviewed, an unknown `item` / `tab` / `sort`, a note that's too long | `message` as-is |
| `401` / `403` | Not signed in / not an ops admin | Send them to sign in |
| `404` | Unknown driver, or no licence check yet | `message` |
| `502` / `503` | A backing service is down | "Couldn't load, try again" |

---

## 11. Where the mock-ups differ from the app

- **Documents column chips:** the mock-ups show *National ID, Live Selfie, Car Ownership*. Use the four item labels instead: Driver's licence, Vehicle registration, Insurance certificate, Roadworthiness certificate.
- **Driver's Licence screen:** the button says *Verify NIN*; it should say **Verify Licence**. Add Smile ID's finding and job beside the image.
- **Insurance Certificate screen:** it shows a vehicle ownership certificate and an *Approve Car Ownership* button. Both should be insurance.
- **"PDF" badge:** use `file.fileType`, since documents can be photos or PDFs.
- **Status in the summary box:** use `overallStatus`. Show `IN_REVIEW` as "In review", not "PENDING".
- **Driver ID:** it's `D-` plus the first six characters of the driver's id (`D-4F2A91`), not a running number (`D-001`).
- **"Awaiting Smile ID":** this state isn't in the mock-ups. Add it for licence chips and the licence document row.

---

## 12. Checklist

**Queue**
- [ ] Tabs with counts (`/queue/counts`), using the same `search` as the list.
- [ ] Search, column sorting (`sort` + `direction`), page size and paging.
- [ ] Rows: ref, name, phone, `submittedAt`, document chips from `items[].label`, "In queue" from `waitingSince`.
- [ ] "Awaiting Smile ID" chips for `awaitingProvider`.

**Review summary**
- [ ] Name, phone, submitted, in queue, `overallStatus` badge.
- [ ] The four documents from `review.items`, with ✓ / ✗ / none.
- [ ] **Approve Driver** follows `canApprove`, showing `approveBlockedReason` when disabled; **Reject** follows `canReject`.
- [ ] Refresh the page from each action's response.

**Document screens**
- [ ] Licence: details, the Smile ID finding, and images from `/verification/licence`, loaded each time and never stored.
- [ ] Vehicle: details, `insuranceExpiresOn`, exterior and interior photos, AC.
- [ ] Insurance and Roadworthiness: file and expiry.
- [ ] Preview from `file.url`, badge from `file.fileType`, **Download** from `file.downloadUrl`.
- [ ] **Verify**, **Flag** (note required, max 255 characters) and **Reset to Default**.

**Per-document actions**
- [ ] **Approve** on each document, following `canApproveItem`; note box required when `approveItemNeedsNote`.
- [ ] **Request resubmission** on each document, following `canRequestResubmission`; reason required (max 255).
- [ ] "Smile ID: …" from `providerOutcome` on the licence; "Verified by Smile ID" and no Flag / Request resubmission when `lockedBySmileId`.
- [ ] "Waiting for the driver to re-upload" from `resubmissionRequests`.

**Other**
- [ ] Audit log tab or drawer (`/verification/history`), with `action`, before → after, and an "override" chip from `overrodeProvider`.
- [ ] Show every `400` `message` as-is.

---

## 13. What the driver app sees

The driver app reads its documents from `GET /profile/drivers/{driverId}/verification-status` (the driver's own id) and the licence from `GET /profile/drivers/me`. Each decision also sends a push (and an email when the driver has one), as decisions always have.

| Ops action | Push (title / body) | What the app reads afterwards |
|---|---|---|
| **Approve** one document / **Approve Driver** | "Driver's licence approved" / "Your driver's licence has been approved." | The item's `status: "APPROVED"`. With all four approved, `overallStatus: "APPROVED"` and the driver can post (plus the usual "You're verified" push the first time). |
| **Request resubmission** | "Please upload your insurance certificate again" / the `reason` | The item's `status: "REJECTED"` with `rejectionReason` = the reason, and an entry in **`resubmissionRequests`** (`item`, `label`, `reason`, `requestedAt`). `overallStatus: "REJECTED"`; an approved driver can't post until the new upload is approved. |
| **Reject** / **Save Changes** with a flag | "Insurance certificate needs attention" / the flag's note | `status: "REJECTED"`, `rejectionReason`. Not in `resubmissionRequests` (that's only for an explicit request). |

**Re-uploading:**
- Insurance / roadworthiness: `POST /profile/drivers/me/documents` again.
- Vehicle: `PUT /profile/vehicles/{vehicleId}`.
- Licence: a new Smile ID check (`POST /profile/drivers/me/licence-check/session`), or on older builds `PUT /profile/drivers/me` with `governmentIdUrl`.

The document goes back to `IN_REVIEW` (a new licence check: `PENDING` while Smile ID checks it, then approved or in review), leaves `resubmissionRequests`, and the driver shows under **Resubmitted** in the queue.

Push `type`s are unchanged: `VERIFICATION_ITEM_APPROVED`, `VERIFICATION_ITEM_REJECTED` (a resubmission request uses the rejected type, with its own title).

# Ops Console: Drivers Directory

Sidebar: **Drivers → Drivers Directory**.

Every driver, with their car, trips, rating, earnings, reliability and status. From a row, ops opens the driver's profile, then their documents. They can suspend or reinstate the driver, sign them out everywhere, and edit their details and vehicle; see [accounts.md](accounts.md).

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come with `success: false` and a `message` you can show as-is.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Header "N total drivers" and the tab badges | `GET /drivers/counts` |
| Table: tabs, search, sorting, paging | `GET /drivers` |
| **View**: the driver's profile, with every section | `GET /drivers/{driverId}`; see [accounts.md](accounts.md) section 3 |
| **Suspend Driver** / reinstate / sign out everywhere | `POST /users/{driverId}/suspend` · `/reinstate` · `/sign-out-everywhere`; see [accounts.md](accounts.md) section 2 |
| Edit name / email · edit vehicle | `PATCH /users/{driverId}/details` · `PATCH /drivers/{driverId}/vehicles/{vehicleId}` |
| Give licence tries back | `POST /drivers/{driverId}/verification/licence/reset-attempts` |
| Account history | `GET /users/{driverId}/account-history` |
| **View Document**: the Documents list with ✓ / ✗ | `GET /drivers/{driverId}/verification` |
| Each document's screen (licence, vehicle, insurance, roadworthiness) | Same as the Verification Queue; see [verification-queue.md](verification-queue.md) sections 4–5 |
| Flag / Verify / Reset on a document | `PUT` / `DELETE /drivers/{driverId}/verification/items/{item}/review`; see section 6 |
| **Save Changes** on the Documents list | `POST /drivers/{driverId}/verification/save`; see section 6 |

---

## 2. Table: `GET /drivers`

| Param | Default | Values |
|---|---|---|
| `status` | none = **All** | `VERIFIED` · `PENDING` · `RESUBMITTED` · `SUSPENDED` |
| `search` | none | Name, driver ref (`D-4F2A91`; the `D-` is optional), phone (4 or more digits), or plate (`KJA100`, spaces and dashes ignored) |
| `sort` | newest sign-up first | `DRIVER_REF` · `NAME` · `VEHICLE` · `TRIPS` · `RATING` · `EARNINGS` · `RELIABILITY` · `STATUS` · `JOINED` |
| `direction` | `ASC` | `ASC` · `DESC` |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

**Sorting:** missing values (no trips yet, no rating yet) always go last, whichever direction you sort. An unknown `status` or `sort` gets a `400`.

```json
{
  "content": [
    {
      "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "vehicle": { "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA" },
      "trips": 213,
      "rating": 4.88, "ratingCount": 40,
      "earnings": 1847200.00, "grossEarnings": 1944421.05,
      "reliabilityPercent": 91,
      "status": "VERIFIED",
      "verificationStatus": "APPROVED",
      "joinedAt": "2026-01-12T09:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 21, "totalPages": 2, "last": false
}
```

| Column | From |
|---|---|
| Action | **View** opens `GET /drivers/{driver.id}` |
| Driver ID | `driver.ref` |
| Name, phone under it | `driver.name`, `driver.phone` |
| Vehicle | `vehicle.make vehicle.model vehicle.year`, with `vehicle.plateNumber` under it. `vehicle` is `null` until the driver adds a car; show a dash. |
| Trip | `trips`: rides completed on the driver's trips |
| Rating | `rating` (average out of 5). `null` means no ratings yet. |
| Earning | `earnings`: lifetime takings after CoRyd's commission, in naira. `grossEarnings` is before commission. |
| Reliability | `reliabilityPercent` for the bar. The share of paid seats the driver completed rather than cancelled. `null` until they've completed or cancelled one; show "–". |
| Status | `status`; see below |
| Footer "Showing 1–10 of N" | `page`, `size`, `totalElements` |

**Suggested bar colours for reliability:** 85% and above teal, 70–84% amber, below 70% red.

**`status`**: each driver has exactly one, the first that applies:

| Value | Meaning | Badge |
|---|---|---|
| `SUSPENDED` | Ops suspended the account | red "✕ SUSPENDED" |
| `VERIFIED` | All four documents approved; the driver can post trips | green "✓ VERIFIED" |
| `RESUBMITTED` | The driver re-uploaded something ops rejected, and it's waiting again | amber "RESUBMITTED" |
| `PENDING` | Anything else: not finished, waiting for review, or rejected | amber "PENDING" |

`verificationStatus` is the documents' overall state: `PENDING`, `IN_REVIEW`, `APPROVED`, `REJECTED` or `EXPIRED`. A suspended driver keeps theirs, so you can show both.

**Where the numbers come from:**
- Profile-service keeps one indexed row per driver, and the list is filtered, sorted and paged by the database. It stays fast however many drivers there are.
- Trips, rating, reliability and reports come from trip-service. Earnings come from payment-service. Suspension comes from identity-service.
- Each is updated within seconds of a change, and every driver is re-synced nightly.
- A driver who hasn't driven or earned yet shows `null` for those numbers; render a dash.

### Tab badges: `GET /drivers/counts?search=`
```json
{ "all": 21, "verified": 12, "pending": 5, "resubmitted": 2, "suspended": 2 }
```

- Each driver is counted once.
- Send the same `search` as the table.
- The header "N total drivers in the pilot corridor" is `all` with no search.

**Tabs:** the designs show **All · Verified · Pending · Suspended** in one version and **All · Verified · Pending · Resubmitted** in another. All five values are available, so the team can show either set or all of them.

---

## 3. Driver profile: `GET /drivers/{driverId}`

```json
{
  "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Emeka Okafor", "phone": "09036828299", "photoUrl": "…" },
  "status": "VERIFIED",
  "verificationStatus": "APPROVED",
  "suspension": null,
  "vehicle": { "make": "Toyota", "model": "Camry", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA" },
  "performance": {
    "totalTrips": 415, "rating": 4.88, "ratingCount": 312,
    "totalEarnings": 803200.00, "grossEarnings": 845473.68,
    "reliabilityPercent": 97, "reportsFiled": 0
  },
  "account": { "ref": "D-4F2A91", "joinedAt": "2026-01-12T09:00:00Z" }
}
```

| Shown | From |
|---|---|
| Header: name, ref | `driver.name`, `driver.ref` |
| Card: photo, short name ("Emeka O."), stars and count, phone | `driver.photoUrl`, `driver.name`, `performance.rating` (`ratingCount`), `driver.phone` |
| "Verified Driver" badge | Show when `verificationStatus` is `APPROVED` |
| Vehicle: make & model, plate | `vehicle` |
| Performance: Total Trips, Rating, Total Earning, Reliability Score, Reports Filed | `performance.*`. **Reports filed** counts problems riders reported on this driver's trips. |
| Account: Driver ID, Joined | `account.ref`, `account.joinedAt` |
| Suspended banner | `suspension.suspendedAt` and `suspension.reason`, when `suspension` isn't `null` |

An unknown driver gets `404 Driver not found`.

**The same call also returns the rest of the profile:**
- the account, with status, roles, last seen and devices;
- `email`;
- `verification`, with the documents, licence tries and identity;
- every vehicle;
- emergency contacts;
- suspension history;
- recent account history;
- any pending deletion.

The `account` object gains fields and `suspension` gains `suspendedBy`. See [accounts.md](accounts.md) section 3 for the full shape.

---

## 4. Suspend and reinstate

**Suspend:** `POST /users/{driverId}/suspend`

```json
{ "reason": "Threatened a rider" }
```

- The reason is required (up to 500 characters). It's kept on the account and shown back in `suspension.reason`.
- The driver can't sign in again, or post or book.
- Their upcoming trips are cancelled, and riders are refunded.

**Reinstate:** `POST /users/{driverId}/reinstate`, with `{ "reason": "…" }`. **The reason is required.**

**What changes now:**
- Suspending ends the driver's sessions on every device **within seconds**.
- Reinstating doesn't revive those sessions; the driver signs in again.
- A trip already in progress is never interrupted.
- Both are kept in `suspensionHistory` and the account history.

See [accounts.md](accounts.md) section 2 for the errors and what the app shows.

**After either one,** refetch the profile and the counts.

**Buttons:**
- Show **Suspend Driver** when `status` isn't `SUSPENDED`.
- Show **Reinstate Driver** when it is. The design doesn't have this button yet; it should be added.
- Ask for the reason in a dialog before suspending.

---

## 5. Documents: `GET /drivers/{driverId}/verification`

This is the same response as the Verification Queue's review page; see [verification-queue.md](verification-queue.md) section 4. The Documents list and every document screen work exactly as they do in the queue.

| Row | Status |
|---|---|
| Driver's licence | `governmentId.status` |
| Vehicle registration | `vehicleRegistration.status` |
| Insurance certificate | `documents[]` entry with `type: INSURANCE` |
| Roadworthiness | `documents[]` entry with `type: ROADWORTHINESS_CERT` |

**The ✓ / ✗ next to each document:**
- A reviewer's mark, from `review.items[].mark`, shows first: ✓ for `VERIFIED`, ✗ for `FLAGGED`.
- With no mark: ✓ when the document is `APPROVED`, ✗ when it's `REJECTED` or `EXPIRED`.

**View** opens the same document screens as the Verification Queue: the file, Smile ID's licence images (`GET /drivers/{driverId}/verification/licence`), vehicle details, and the photos. **Files are kept once approved**, so a verified driver's documents can always be viewed and downloaded.

---

## 6. Flag, Verify and Save Changes

The document screens' buttons work here too, including on documents that are already approved.

| Button | Call |
|---|---|
| **Flag** (note required, max 255 characters; it's the reason the driver sees) | `PUT /drivers/{driverId}/verification/items/{item}/review` with `{ "mark": "FLAGGED", "note": "…" }` |
| **Verify** / **Approve …** | `PUT /drivers/{driverId}/verification/items/{item}/review` with `{ "mark": "VERIFIED" }` |
| **Reset to Default** | `DELETE /drivers/{driverId}/verification/items/{item}/review` |
| **Save Changes** | `POST /drivers/{driverId}/verification/save` |

**Marks are saved on the server as they're made.** They appear in `review.items[].mark`, and **Save Changes** applies them:

| Mark | Becomes |
|---|---|
| Flagged | **Rejected**, with the note as the reason. An approved document loses its approval. |
| Verified, on a document waiting for ops | **Approved** |
| Verified, on an approved document | Unchanged |
| Unmarked | Unchanged |

**Save Changes** is enabled when `review.canSave` is true. With nothing to save, you get `400 Nothing to save - verify or flag a document first`.

**After saving:**
- The driver gets a push for each changed document. A rejected one shows its reason in the app, and the driver re-uploads it.
- Rejecting a document of a verified driver **stops them posting trips** until it's approved again. Their row moves to `PENDING`, then to `RESUBMITTED` once they re-upload.
- Every mark is cleared.
- **Returns** the documents response with the new states.

**Cancel** closes the list. Marks already made stay saved until reset, and are shown next time.

---

## 7. Where the designs differ from the app

- **Insurance Certificate screen:** it shows a *vehicle ownership certificate* and an **Approve Car Ownership** button. Both should be insurance.
- **Driver's Licence screen:** the button reads **Verify NIN**; it should be **Verify Licence**.
- **Driver ID:** it's `D-` plus the first six characters of the driver's id (`D-4F2A91`), not a running number (`D-001`). The documents header shows a different ID from the profile (`D-1041` vs `D-001`); use `driver.ref` in both.
- **Reinstate:** there's no button for a suspended driver yet; add **Reinstate Driver**, with a reason dialog.
- **"PDF" badge:** use `file.fileType`, since documents can be photos or PDFs.

---

## 8. Checklist

- [ ] Header total and tab badges from `/drivers/counts`, using the same `search` as the table.
- [ ] Table from `/drivers`: tabs (`status`), search, sortable columns (`sort` + `direction`), page size and paging.
- [ ] Reliability bar colour from `reliabilityPercent`; dashes for `null` numbers.
- [ ] Profile from `/drivers/{driverId}`: card, Verified badge, vehicle, performance, account, suspension banner.
- [ ] **Suspend Driver** and **Reinstate Driver**, both with a reason dialog, refreshing afterwards.
- [ ] Profile sections, edits, account history and licence tries; see [accounts.md](accounts.md) section 11.
- [ ] Documents list from `/drivers/{driverId}/verification`, with ✓ / ✗ and **View** to the document screens.
- [ ] Flag / Verify / Reset on each document screen (the same calls as the Verification Queue).
- [ ] **Save Changes** calls `/verification/save`, enabled by `review.canSave`.

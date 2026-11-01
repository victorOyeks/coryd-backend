# Ops Console: Document Expiry Tracker

Sidebar: **Drivers → Document Expiry**.

Every driver's insurance and roadworthiness certificate against its expiry date. Ops sees what's expiring or expired and can remind a driver, or every driver in a tab, with a push. Reminders and expiry also happen automatically every morning.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. Without a token you get `401`; with another role, `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come with `success: false` and a `message` you can show as-is.

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| The three cards and the tab badges | `GET /document-expiry/counts` |
| Table: tabs, search, sorting, paging | `GET /document-expiry` |
| A row's **Notify** | `POST /document-expiry/{documentId}/notify` |
| **Notify all** | `POST /document-expiry/notify-all` |

---

## 2. Table: `GET /document-expiry`

| Param | Default | Values |
|---|---|---|
| `tab` | none = **All** | `EXPIRING_SOON` · `EXPIRED` · `VERIFIED` |
| `search` | none | Driver name, driver ref (`D-4F2A91`; the `D-` is optional), phone (4 or more digits), or document (`insurance`, `roadworthiness`; 3 or more letters) |
| `sort` | most urgent first: longest expired, then soonest to expire | `DRIVER_REF` · `NAME` · `EXPIRY_DATE` · `DOCUMENT` · `DAYS_LEFT` · `LAST_NOTIFIED` · `STATUS` |
| `direction` | `ASC` | `ASC` · `DESC` |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

**Sorting:**
- `DAYS_LEFT` sorts the same as `EXPIRY_DATE`.
- `STATUS` sorts in this order: expired, expiring soon, verified, not verified.
- Blanks (never notified) always go last.
- An unknown `tab` or `sort` gets a `400`.

```json
{
  "content": [
    {
      "documentId": "9b1c…",
      "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Emeka Okafor", "phone": "08053636321", "photoUrl": "…" },
      "documentType": "ROADWORTHINESS_CERT",
      "documentLabel": "Roadworthiness Certificate",
      "expiresOn": "2026-09-02",
      "daysLeft": -3,
      "status": "EXPIRED",
      "verificationStatus": "EXPIRED",
      "verifiedAt": null,
      "lastNotifiedAt": "2026-09-03T06:00:00Z",
      "lastNotifiedBy": "system",
      "notifiedRecently": false,
      "canNotify": true
    }
  ],
  "page": 0, "size": 10, "totalElements": 14, "totalPages": 2, "last": false
}
```

| Column | From |
|---|---|
| Action | **Notify** when `canNotify`. Show **Notified** (green) when `notifiedRecently`. Show nothing on the Verified tab, where `canNotify` is always false. |
| Driver ID | `driver.ref` |
| Name, phone under it | `driver.name`, `driver.phone`. Both are `null` for a driver just signed up whose details haven't reached the directory yet, usually for a few seconds; show the Driver ID alone. That row can still be reminded. |
| Expiry date | `expiresOn` (a date with no time, e.g. `2026-09-02`) |
| Documents | `documentLabel`. Colour the badge by `documentType`. |
| Days left / ago | `daysLeft`. Positive: "5d". `0`: "Today". Negative: "Expired", or "3d ago". |
| Days ago (Verified tab) | Days since `verifiedAt`, the date ops approved the document |
| Last notify | `lastNotifiedAt` as a date, or "—" when it's `null`. `lastNotifiedBy` is the admin's name, or `system` for the automatic reminders. |
| Status | `status`; see below |
| Footer "Showing 1–10 of N" | `page`, `size`, `totalElements` |

**`status`**: each document has exactly one.

| Value | Meaning | Badge |
|---|---|---|
| `EXPIRED` | The expiry date has passed | red "✕ EXPIRED" |
| `EXPIRING_SOON` | Expires today or within the next 14 days | amber "EXPIRING SOON" |
| `VERIFIED` | Approved, with more than 14 days left | green "✓ VERIFIED" |
| `NOT_VERIFIED` | More than 14 days left but not approved: waiting in the Verification Queue, or rejected | grey "NOT VERIFIED" |

**Notes:**
- Days are counted in Lagos time.
- A document whose expiry date is today is still `EXPIRING_SOON`; it becomes `EXPIRED` the next morning.
- `verificationStatus` is the document's own review state (`APPROVED`, `IN_REVIEW`, `REJECTED`, `EXPIRED`…). It's what the Verification Queue and the driver's app show.

---

## 3. Cards and tab badges: `GET /document-expiry/counts?search=`

```json
{ "all": 14, "expiringSoon": 9, "expired": 5, "verified": 0, "notVerified": 0, "withoutExpiryDate": 2 }
```

| Shown | From |
|---|---|
| Total Tracked Documents | `all` |
| Expiring within 14 days | `expiringSoon` |
| Expired (auto-suspended) | `expired` |
| Tab badges: All · Expiring Soon · Expired · Verified | `all` · `expiringSoon` · `expired` · `verified` |

- Each document is counted once: `expiringSoon + expired + verified + notVerified = all`.
- **For the cards, call it with no `search`.** For the tab badges, send the same `search` as the table.
- Documents whose driver isn't in the directory yet are still listed and counted. They just can't be found by name or phone until the directory catches up.
- `withoutExpiryDate` counts documents uploaded with no expiry date. They can't be tracked and aren't in the table; see section 6.

---

## 4. Notify and Notify all

**Notify:** `POST /document-expiry/{documentId}/notify`, with no body.

- The driver gets a push:
  - **Expiring:** "Your insurance certificate expires in 3 days. Upload the renewed one before then to keep posting trips." Or "…expires tomorrow." / "…expires today."
  - **Expired:** "Your insurance certificate expired on 2 Sep 2026. Upload a current one to post trips again."
- The reminder is recorded under the signed-in admin's name.
- **Returns** the updated row, with `notifiedRecently: true`, so you can swap the button to **Notified** without refetching.
- **Errors** (`400`, with a message you can show):
  - A document that isn't due yet: "Only a document expiring within 14 days, or already expired, can be sent a reminder".
  - A driver reminded within the last 24 hours: "The driver was reminded at 1 Nov, 10:00 AM - you can remind them again after 2 Nov, 10:00 AM". The automatic reminders count too.
  - An unknown document: `404 Document not found`.

**Notify all:** `POST /document-expiry/notify-all?tab=&search=`

- Send the same `tab` and `search` as the table.
- It reminds every expiring or expired document they match, on every page, not just the one showing.
- Documents reminded within the last 24 hours are left out.
- On the Verified tab there's nothing to send. The design has no Notify all button there; keep it that way.

```json
{ "notified": 8, "skippedRecentlyNotified": 1 }
```

**After a Notify all,** show a toast like "8 drivers reminded, 1 already reminded today". Then refetch the table. The button can show **Notified all** until the tab or search changes.

The 24-hour wait is a setting (`DOCUMENT_EXPIRY_NOTIFY_COOLDOWN`), so ask the backend team if ops wants it shorter.

---

## 5. What happens on its own

Every morning at 7:00 Lagos time:

1. **Expiry.** A document past its date becomes `EXPIRED` and the driver gets the "expired" push. Their app shows the document as expired with a reason, so they know to upload a new one. **They can't post new trips** until a current one is approved.
2. **Reminders.** A push goes out 14 days and 3 days before the expiry date, once each. A document uploaded with only a few days left gets one reminder, not two. Each reminder sets `lastNotifiedAt` (by `system`).
3. **Renewal.** The driver uploads the renewed document in the app. It goes to the **Verification Queue**, and once ops approves it, the driver can post again. They don't get the "You're verified!" welcome a second time. The new expiry date starts its own reminders.

---

## 6. What doesn't fit how the app works

- **Only insurance and roadworthiness have expiry dates.**
  - The designs also show **Vehicle Reg**, **National ID** and **Car Ownership**. None of those can be tracked:
    - Vehicle registration is the car's details (make, model, plate, photos), with no expiry date.
    - The NIN doesn't expire.
    - The app doesn't collect a car ownership document.
  - The page subtitle ("Insurance & roadworthiness") is right; the badges should be insurance and roadworthiness only.
  - **The driver's licence does expire,** and Smile ID reads its expiry date, but the app doesn't keep it. Adding licences to this tracker is a backend change; ask if you want it.
- **"Expired (auto-suspended)" doesn't mean the account is suspended.**
  - An expired document stops the driver **posting new trips**, but they can still sign in.
  - **Trips they already posted still go ahead.** If ops wants those cancelled too, that's a change to make.
  - Suspending the account is a separate action, in the Drivers Directory.
  - Suggest renaming the card to "Expired (can't post trips)".
- **Documents can be uploaded without an expiry date.**
  - The app doesn't require one, so those documents can't be tracked. They're counted in `withoutExpiryDate` and aren't in the table.
  - Making the expiry date required at upload is an app change, and it should be made.
- **Card numbers:** the design's cards don't add up (14 total, 9 expiring, 5 expired, plus 6 verified on the tab). Here each document is counted exactly once.
- **The second design shows "EXPIRING SOON" on expired rows.** Use `status`, which is always one value per row.
- **Verified tab "Days ago":** taken as days since the document was approved (`verifiedAt`). If it was meant to be something else, say what.
- **Driver ID:** it's `D-` plus the first six characters of the driver's id (`D-4F2A91`), not a running number (`D-001`).
- **Reminders are pushes only.** No SMS or email is sent.

---

## 7. Checklist

- [ ] Cards from `/document-expiry/counts` with no search; tab badges with the table's `search`.
- [ ] Table from `/document-expiry`: tabs (`tab`), search, sortable columns (`sort` + `direction`), page size and paging.
- [ ] Days left/ago from `daysLeft`; "Days ago" on the Verified tab from `verifiedAt`.
- [ ] **Notify** when `canNotify`, **Notified** when `notifiedRecently`; show the `400` message when it's too soon.
- [ ] **Notify all** with the same `tab` and `search`; toast from `notified` / `skippedRecentlyNotified`, then refetch.
- [ ] Only insurance and roadworthiness badges; rename "auto-suspended".

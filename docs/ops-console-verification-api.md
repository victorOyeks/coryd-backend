# Ops Console: Driver Verification API

Base URL: `https://api-dev.coryd.ng/ops`. Every endpoint needs an **`OPS_ADMIN`** token (`Authorization: Bearer …`).
Responses use the standard envelope `{ responseId, timestamp, success, message, data }`. Errors return `success: false`
with a `message` you can show as-is.

A driver goes live once ops approves all four items:

| Item (`item` value) | Shown as | What ops looks at | Automatic check (Prembly) |
|---|---|---|---|
| `GOVERNMENT_ID` | Driver's licence | Licence photo, licence number, date of birth | `DRIVERS_LICENCE`: FRSC record and name match |
| `VEHICLE` | Vehicle registration | Make, model, year, colour, plate, exterior and interior photos, AC | `VEHICLE_PLATE`: plate register, make and colour |
| `INSURANCE` | Insurance | Certificate image, expiry date | none |
| `ROADWORTHINESS_CERT` | Roadworthiness | Certificate image, expiry date | none |

National ID, Live Selfie and Car Ownership are gone from the flow. Insurance takes Car Ownership's place.

---

## 1. Queue list: `GET /ops/drivers/verification/queue`

| Param | Default | Values |
|---|---|---|
| `tab` | none (All) | `RESUBMITTED` · `IN_REVIEW` · `PENDING`. **Leave it out for the All tab.** |
| `search` | none | Driver name, or the driver ref (`D-4F2A91`, the `D-` is optional) |
| `page` | `0` | 0-based |
| `size` | `20` | max `50` |

Shows every driver who has at least one item waiting for a decision. **The longest-waiting driver comes first.**

Each driver appears under exactly one tab, the first rule that matches:
- **Resubmitted:** they re-uploaded something ops had rejected.
- **In review:** some items are decided and others are still waiting.
- **Pending:** they submitted and nothing has been decided yet.

```json
{
  "content": [
    {
      "driver": { "id": "4f2a91c0-…", "ref": "D-4F2A91", "name": "Tunde Bakare", "phone": "08011112222", "photoUrl": "…" },
      "tab": "RESUBMITTED",
      "waitingSince": "2026-10-03T08:00:00Z",
      "items": [
        { "item": "GOVERNMENT_ID", "status": "APPROVED", "submittedAt": "2026-10-02T17:10:00Z", "resubmitted": false },
        { "item": "VEHICLE", "status": "IN_REVIEW", "submittedAt": "2026-10-03T08:00:00Z", "resubmitted": true },
        { "item": "INSURANCE", "status": "IN_REVIEW", "submittedAt": "2026-10-02T17:12:00Z", "resubmitted": false },
        { "item": "ROADWORTHINESS_CERT", "status": "PENDING", "submittedAt": null, "resubmitted": false }
      ]
    }
  ],
  "page": 0, "size": 20, "totalElements": 9, "totalPages": 1, "last": true
}
```

How the design maps to the response:
- **Driver ID, name, phone:** `driver.ref`, `driver.name`, `driver.phone`.
- **Submitted:** the earliest `submittedAt` among the items.
- **In queue for:** time since `waitingSince`, which is when the oldest still-waiting item came in.
- **Documents column:** `items`. `PENDING` means not uploaded yet, `IN_REVIEW` means waiting for ops.
- **Actions:** **View** only. It opens the review page (section 2).

If a phone number can't be loaded, `driver.phone` comes back `null` and the row still shows. Render a dash.

### Tab counts: `GET /ops/drivers/verification/queue/counts?search=`

```json
{ "all": 9, "pending": 4, "inReview": 3, "resubmitted": 2 }
```

Pass the same `search` as the list so the badges match what's listed.

---

## 2. Review page: `GET /ops/drivers/{driverId}/verification`

```json
{
  "driver": {
    "id": "4f2a91c0-…", "ref": "D-4F2A91", "firstName": "Tunde", "lastName": "Bakare",
    "phone": "08011112222", "photoUrl": "…", "licenceNumber": "LAG12345678", "dateOfBirth": "1990-01-01"
  },
  "waitingSince": "2026-10-03T08:00:00Z",
  "governmentId": { "status": "IN_REVIEW", "rejectionReason": null, "url": "https://…/licence.jpg",
                    "submittedAt": "2026-10-02T17:10:00Z", "resubmitted": false },
  "vehicleRegistration": {
    "status": "IN_REVIEW", "rejectionReason": null, "submittedAt": "2026-10-03T08:00:00Z", "resubmitted": true,
    "vehicle": { "make": "Toyota", "model": "Corolla", "year": 2019, "color": "Black", "plateNumber": "LND-441-AA",
                 "exteriorPhotoUrl": "…", "interiorPhotoUrl": "…", "hasAC": true, "acConfirmedWorking": true }
  },
  "documents": [
    { "type": "INSURANCE", "status": "IN_REVIEW", "rejectionReason": null, "expiresOn": "2027-06-30",
      "documentUrl": "…", "submittedAt": "2026-10-02T17:12:00Z", "resubmitted": false }
  ],
  "overallStatus": "IN_REVIEW",
  "verifiedSince": null,
  "providerChecks": [
    { "type": "DRIVERS_LICENCE", "outcome": "MATCH", "nameMatchScore": 0.96,
      "summary": "FRSC record found; name matches", "checkedAt": "…" },
    { "type": "VEHICLE_PLATE", "outcome": "DETAILS_MISMATCH", "nameMatchScore": null,
      "summary": "Plate registered as a silver Toyota", "checkedAt": "…" }
  ]
}
```

How the review page maps to the response:
- **Driver's licence card:** `governmentId` for the status and image, plus `driver.licenceNumber` and `driver.dateOfBirth`. Show the `DRIVERS_LICENCE` provider check beside it.
- **Vehicle registration card:** `vehicleRegistration.vehicle`. Show the `VEHICLE_PLATE` provider check beside it.
- **Insurance and Roadworthiness cards:** the matching entry in `documents`. An item missing from the list hasn't been uploaded yet.
- **"Resubmitted" tag on a card:** `resubmitted: true`.

Prembly check outcomes, to use as badge colours:

| `outcome` | Meaning | Suggested badge |
|---|---|---|
| `MATCH` | Record found and details agree | green |
| `NAME_MISMATCH` | Licence found, but the name differs from what the driver entered | amber |
| `DETAILS_MISMATCH` | Plate found, but the make or colour differs | amber |
| `NOT_FOUND` | No such licence or plate | red |
| `PROVIDER_ERROR` | Prembly couldn't be reached. This is not the driver's fault. | grey: "Check unavailable" |

Checks are advisory. Ops always makes the final call. `providerChecks` is empty if Prembly hasn't been asked yet.

---

## 3. Approve or reject: `POST /ops/drivers/{driverId}/verification/decisions`

Each card has **Approve** and **Reject**. Reject opens a dialog that asks for the reason for **that item**. There's no shared rejection box on the page. The page keeps its marks locally and sends them in one call when the reviewer submits:

```json
{
  "decisions": [
    { "item": "GOVERNMENT_ID", "status": "APPROVED" },
    { "item": "VEHICLE", "status": "REJECTED", "rejectionReason": "Plate in the photo doesn't match LND-441-AA" },
    { "item": "INSURANCE", "status": "REJECTED", "rejectionReason": "Certificate expired in August" }
  ]
}
```

Rules:
- `status` is `APPROVED` or `REJECTED`.
- Each rejected item needs its own `rejectionReason`, up to 255 characters. The driver sees it in the app against that item.
- An item can appear only once per call. Items you leave out keep their current status.
- If any rule fails, **nothing is saved**, and you get a `400` such as `Give a reason for rejecting the vehicle registration`.
- The reviewer is recorded as the signed-in admin automatically.

The response has the same shape as section 2, so the page can refresh from it. The driver gets a push for every decision, and goes live once all four items are approved.

When a driver re-uploads a rejected item, it goes back to `IN_REVIEW` with `resubmitted: true`, and the driver moves to the **Resubmitted** tab.

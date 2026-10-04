# What Changed — FE Migration Guide

Everything from this round of backend work that the app has to react to, in one place. Read the
**Breaks the app** section first; the rest can be picked up in any order.

Most of this is a single release. Treat it as one migration, not several.

---

## Breaks the app if you do nothing

These change payloads the app already reads or sends. Shipping the backend without the app
catching up means 400s or blank fields.

### 1. `fullName` is gone — names are two fields now

`legalName` and `fullName` have been replaced by `firstName` and `lastName` everywhere.

| Where | Was | Now |
|---|---|---|
| `PUT /profile/drivers/me` | `legalName` | `firstName` + `lastName`, **both required** |
| `PUT /profile/riders/me` | `fullName` | `firstName` (required) + `lastName` |
| Driver/rider summaries, search results, chat, booking manifest | `legalName` / `fullName` | `firstName` + `lastName` |
| Earnings trip list `rider` block | `fullName` | `firstName` + `lastName` |
| Login / refresh response | `fullName` | `fullName` **kept** + `firstName` + `lastName` |

**The login response deliberately still has `fullName`**, carrying the first name only, exactly as
before. Nothing breaks the moment the backend deploys. Move to `firstName` at your own pace.

Join the two yourself for display. The split exists because FRSC matches on a surname, and a single
name field can only ever be compared whole.

**Existing accounts** were migrated automatically: first word becomes the first name, **last** word
becomes the surname. Middle names were dropped. Expect a few support tickets from users who want
theirs back.

### 2. The selfie is gone

`SELFIE` is no longer a verification item. Remove the capture screen, stop sending `selfieUrl` and
`selfiePublicId` on the profile save, and drop `selfieSubmitted` from the onboarding checklist. The
server no longer accepts or returns any of them.

Identity now rests on the driver's licence alone, which is checked against FRSC automatically.

### 3. New required fields on driver profile save

`PUT /profile/drivers/me` now **rejects** a save without these:

| Field | Why |
|---|---|
| `idNumber` | The driver's licence number — what FRSC is queried with |
| `dateOfBirth` | Required alongside it |

And `idNumber` is validated: **8–20 characters, letters and numbers only, at least one of each**.
Spaces and hyphens are stripped, and it is uppercased before storage — so `aad 2320-823198` is
accepted and stored as `AAD2320823198`. Anything else gets:

> Enter your driver's licence number as it appears on your licence - letters and numbers only, 8 to 20 characters

Validate client-side with the same rule so the driver finds out before they submit.

### 4. New required field on vehicle registration

`POST /profile/vehicles/drivers/{driverId}` now requires **`color`**. It's cross-checked against
the national vehicle register, so it can't be blank any more.

### 5. Send `governmentIdPublicId`

`PUT /profile/drivers/me` takes `governmentIdPublicId` beside `governmentIdUrl` — the same
`publicId` that `POST /profile/media/upload` already hands you.

It's optional, so an old build won't break. But **without it the licence image is never deleted
after approval**, which is the bug it was added to fix. Send it whenever you send
`governmentIdUrl`.

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "dateOfBirth": "1990-01-01",
  "idNumber": "ABC12345YZ00",
  "governmentIdUrl": "https://res.cloudinary.com/.../aaed57b6.jpg",
  "governmentIdPublicId": "coryd/uploads/aaed57b6"
}
```

**Expect `governmentId.url` to be `null` once the status is `APPROVED`.** Render approved state from
the status, never from the presence of a URL — the same rule that already applies to approved
vehicle photos and documents.

---

## Two new verification statuses

`VerificationStatus` was `PENDING | APPROVED | REJECTED`. It is now:

| Status | Means | Whose turn |
|---|---|---|
| `PENDING` | Never submitted | **Driver** |
| `IN_REVIEW` | Submitted, sitting with ops | **Ops** — wait state, no action button |
| `APPROVED` | Cleared | — |
| `REJECTED` | Turned down, `rejectionReason` set | **Driver** |
| `EXPIRED` | Was approved, then lapsed | **Driver** |

> **Any exhaustive `when` / `switch` over the old three values will fall through.** Make the mapping
> tolerant of unknown values before this ships.

**Never render `IN_REVIEW` like `PENDING`.** That puts an "Upload your insurance" button in front of
a driver who uploaded it an hour ago.

**`EXPIRED` means renew, not upload.** The driver has done this before; `rejectionReason` names the
document and date. The vehicle is suspended alongside it and also reads `EXPIRED`. Re-uploading
returns both to `IN_REVIEW`.

`overallStatus` follows the same vocabulary: `EXPIRED` beats `PENDING` beats `IN_REVIEW`. So
`overallStatus: "IN_REVIEW"` is your "you're done, we're checking" screen.

Applies to `governmentId.status`, `vehicleRegistration.status`, every entry in `documents[]`,
`overallStatus`, and `verificationStatus` on driver summaries in search results.

---

## Licence and plate are now verified automatically

Saving a licence number fires an FRSC check in the background. The save returns immediately and
never fails because of it.

- **Name on the licence matches the profile** → `governmentId.status` flips to `APPROVED` on its
  own, no ops involved. The driver goes straight to `VEHICLE_PENDING_APPROVAL`.
- **Anything else** — name too different, licence not found, provider down → the item **stays where
  it was** and goes to ops with a note. It is never auto-rejected.

So `IDENTITY_PENDING_APPROVAL` is often over in seconds now rather than days. **Poll
`posting-eligibility` once shortly after a licence save**, or let the driver pull to refresh —
don't assume the state you got back from the save is final.

### `providerChecks` on the verification status

`GET /profile/drivers/{driverId}/verification-status` gained a `providerChecks` array. It is
**advisory** — evidence for ops, not a status:

```json
"providerChecks": [
  {
    "type": "DRIVERS_LICENCE",
    "outcome": "NAME_MISMATCH",
    "nameMatchScore": 0.62,
    "summary": "Licence is held by JOHN ADEYEMI, driver entered Kunle Adeyemi",
    "checkedAt": "2026-09-20T09:14:02Z"
  }
]
```

| `outcome` | Means |
|---|---|
| `MATCH` | Confirmed — for a licence, this is what auto-approved the item |
| `NAME_MISMATCH` | Record exists, name too far off — ops decides |
| `DETAILS_MISMATCH` | Plate found, declared make or colour contradicts the register |
| `NOT_FOUND` | No such licence or plate |
| `PROVIDER_ERROR` | Provider unreachable — our problem, not the driver's |

Show `MATCH` as a small reassurance ("Licence confirmed") if you like. **Do not surface the others
to the driver.** A driver seeing "name mismatch" will assume rejection, and `PROVIDER_ERROR` is us.

A plate `MATCH` does **not** approve the vehicle — the register names a car, not an owner, so ops
still reviews every vehicle.

An empty array means the provider is switched off. Treat it as "no information", not a failure.

---

## Routes can only be posted 3 days ahead

`POST /trip/routes/drivers/{driverId}` rejects a departure more than **3 days** out:

> Routes can only be posted up to 3 days ahead

**Cap the date picker at 3 days** rather than letting a driver fill in the form and get a 400 back.

This exists because drivers are paid on a fixed schedule, and a trip departing after that payout
would leave a refund with nothing to recover from.

---

## Payments and driver earnings

**Superseded by [card-payments-fe-guide.md](card-payments-fe-guide.md).** The wallet has been removed:
riders pay by card (with saved cards for one-tap payment), refunds go back to the card, and drivers
are paid by Paystack straight to their bank every week, with no withdrawals. Follow that guide for the
payment, refund and driver earnings screens.

A driver's routes still can't be booked until they've added a bank account, and posting itself now
needs one too (`PAYOUT_ACCOUNT_REQUIRED`, see
[payout-account-gate-fe-guide.md](payout-account-gate-fe-guide.md)).

---

## Notifications

**Every verification item now notifies on its own.** Approving insurance while roadworthiness is
still outstanding used to be silent. The driver now gets a push — and an email if one is on their
profile — naming the document:

- Approved → *"Insurance certificate approved"*
- Rejected → *"Insurance certificate needs attention"* + that item's own reason

The aggregate "You're verified!" push still fires once, when the whole file clears.

**iOS pushes now have a sound.** Nothing to do on your side; previously every iOS alert arrived
silent because `aps.sound` was never set.

---

## Live search now updates for more than routes appearing and disappearing

`/topic/search-sessions/{sessionId}` previously only pushed when a route entered or left the
results. A route going from 4 seats to 3 pushed nothing — the one number riders watch.

It now pushes whenever **anything on the card** changes: seat count, fare, a driver becoming
verified, the route note. Route cancellation also pushes correctly now; it was silently failing.

**You may receive noticeably more frames.** Each one is the complete, current result list — replace
your list wholesale rather than diffing. That already was the contract; it just fires more often.

See [live-route-search-fe-guide.md](live-route-search-fe-guide.md) for the full mechanism.

---

## Checklist

- [ ] `firstName` / `lastName` sent and read everywhere; `legalName` and `fullName` gone except on the auth response
- [ ] Selfie capture screen removed; `selfieUrl` / `selfiePublicId` no longer sent
- [ ] `idNumber` and `dateOfBirth` required on driver profile save, with client-side licence validation
- [ ] `governmentIdPublicId` sent alongside `governmentIdUrl`
- [ ] `color` required on vehicle registration
- [ ] Status mapping tolerates `IN_REVIEW` and `EXPIRED`, and never renders `IN_REVIEW` as a prompt
- [ ] Approved items render from status, not from the presence of a URL
- [ ] Date picker capped at 3 days ahead
- [ ] Wallet, top-up, pay-from-wallet and Withdraw removed; payments and earnings built per card-payments-fe-guide.md
- [ ] `settlementDebt` surfaced when greater than zero
- [ ] Bank-account setup moved into driver onboarding
- [ ] `posting-eligibility` re-fetched shortly after a licence save
- [ ] Live search replaces the list wholesale on every frame
- [ ] "Driver has not finished setting up payouts" handled as a route problem, not a retryable payment error
- [ ] Refunds described as going back to the card

---

## Release coupling

Two groups:

1. **Everything under "Breaks the app if you do nothing", plus the two new statuses** — these are
   contract changes across several backend services that deploy together. The app needs the matching
   release or it will show blank names and reject unknown statuses.
2. **Everything else** — earnings relabelling, posting cap, live search, notifications — can follow
   independently. The backend is already tolerant of an older app on these.

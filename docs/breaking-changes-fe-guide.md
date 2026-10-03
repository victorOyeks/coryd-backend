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
| Wallet feed `rider` block | `fullName` | `firstName` + `lastName` |
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

## The payment flow, end to end

The API surface the app calls is **unchanged** — same endpoint, same request, same response. What
changed is where the money goes behind it, and that surfaces as two new error cases plus a
different refund destination.

### Paying for a booking

```
POST /payment/payments/initiate
{
  "bookingId": "...",
  "paymentMethod": "PAYSTACK",     // or "WALLET"
  "riderEmail": "rider@example.com",
  "callbackUrl": "coryd://payment-return"
}
```

Response is as before:

| | Means |
|---|---|
| `status: "PENDING"` + `authorizationUrl` | Open the Paystack checkout. Confirmation arrives by webhook, not from this call |
| `status: "PAID"` + `authorizationUrl: null` | Wallet paid synchronously — show success immediately |

**Amount, rider and driver are read from the booking, never from your request.** `amount`, `riderId`
and `driverId` in the body are ignored. Send them or don't; they cannot change what is charged.

### What now happens behind it

On a card payment, Paystack divides the fare the moment it is charged — our commission to us, the
driver's share straight to the driver's own Paystack account. It never passes through CoRyd. The
driver is then paid by Paystack on a fixed weekly schedule.

Nothing about that is visible to the rider. The checkout looks identical.

### Two new failures to handle

**1. The driver hasn't set up payouts** — 400 on `initiate`:

> This driver has not finished setting up payouts, so this trip cannot be booked yet

The driver has no bank account registered, so there is nowhere to send their share. **Treat it as a
route-level problem, not a payment problem.** The rider did nothing wrong and retrying will not
help. Take them back to search and, ideally, stop showing that route.

**2. Nothing else changed in the happy path.** Webhook confirmation, retries returning the same
checkout, double-tap protection — all as they were.

### Refunds now depend on how the rider paid

| Paid with | Refund goes to |
|---|---|
| Card (`PAYSTACK`) | **Back to the card**, via Paystack |
| Wallet (`WALLET`) | **Back to the wallet**, as before |

This is new. Previously every refund became wallet credit regardless of how the rider paid. A card
refund now reverses the original charge, which is the only thing that can reclaim the driver's
share.

**Don't tell the rider to check their wallet after a card refund.** Say "refunded to your card,
allow a few working days" — bank timelines, not ours. The wallet balance will not move.

`REFUND_ISSUED` still fires the same push either way.

---

## Driver earnings: the Withdraw button goes away

This is a screen redesign, not a field rename.

Most of a driver's earnings are now paid **directly into their bank account** by our payment
provider, on a fixed weekly schedule. They don't request it and we don't hold it. Only fares a
rider paid from their CoRyd wallet still arrive as a withdrawable balance.

`GET /payment/earnings/drivers/{driverId}/summary`:

```json
{
  "lifetimeGross": 125000.00,
  "lifetimeNet": 118750.00,
  "todayNet": 2375.00,
  "availableBalance": 1140.00,
  "settlementDebt": 0.00
}
```

| Field | What it means now |
|---|---|
| `lifetimeGross`, `lifetimeNet`, `todayNet` | **Unchanged.** Still total earnings, however they were paid |
| `availableBalance` | **Changed meaning.** No longer "everything you've earned" — only the part paid from rider wallets, which is all that is still withdrawable. Often `0` |
| `settlementDebt` | **New.** What the driver owes back after a refund we couldn't recover. Usually `0` |

**Stop labelling `availableBalance` as "Your earnings".** A driver who earned ₦120,000 this week
may see `availableBalance: 0` and think they've been robbed, when in fact it has all been paid to
their bank. Label it "Available to withdraw" and show `lifetimeNet` / `todayNet` as the earnings
figures.

Suggested shape: **total earned** (from `todayNet` / `lifetimeNet`), **paid to your bank
automatically**, and **available to withdraw** (`availableBalance`) only when it is greater than
zero.

`POST /payment/payouts/drivers/{driverId}` still works, and still withdraws `availableBalance`. It
now fails with *"Insufficient wallet balance"* far more often, simply because that balance is
usually zero.

The transaction feed (`GET /payment/earnings/drivers/{driverId}/transactions`) is unchanged and
remains the full statement.

If `settlementDebt` is greater than zero, say so plainly — that driver's next trips pay it off
before they earn again, and they should not discover it from a surprise zero payout.

### Bank details become mandatory, earlier

A driver's routes **cannot be booked** until they've added a bank account, because that's what
creates their payout destination. A rider trying to pay for such a route gets:

> This driver has not finished setting up payouts, so this trip cannot be booked yet

Move bank-account setup into driver onboarding, before they can post. Today it sits late, as a
prerequisite for withdrawing.

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
- [ ] Driver earnings screen relabelled: `availableBalance` is "available to withdraw", not "your earnings"
- [ ] `settlementDebt` surfaced when greater than zero
- [ ] Bank-account setup moved into driver onboarding
- [ ] `posting-eligibility` re-fetched shortly after a licence save
- [ ] Live search replaces the list wholesale on every frame
- [ ] "Driver has not finished setting up payouts" handled as a route problem, not a retryable payment error
- [ ] Card refunds described as going back to the card, not the wallet

---

## Release coupling

Two groups:

1. **Everything under "Breaks the app if you do nothing", plus the two new statuses** — these are
   contract changes across several backend services that deploy together. The app needs the matching
   release or it will show blank names and reject unknown statuses.
2. **Everything else** — earnings relabelling, posting cap, live search, notifications — can follow
   independently. The backend is already tolerant of an older app on these.

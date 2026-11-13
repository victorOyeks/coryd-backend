# Ops Console: Money

Sidebar: **Transactions**, **Refunds** (manual refund, approvals, refund failures), **Payouts**, **Driver earnings**, **Finance**, **Pricing**.

CoRyd holds no money. Every fare is a card charge on Paystack, split at charge time: the driver's share goes to the driver's
own Paystack subaccount and Paystack settles it to their bank the next working day (a **payout**). Refunds go back to the card
the fare came from. A refunded trip's driver share is recorded as **settlement debt** the driver owes CoRyd, taken from their
next fares (over ₦10,000 they can't post routes until they pay it back by card).

**Basics** (same as the rest of the console):
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** every call needs an **`OPS_ADMIN`** token: `Authorization: Bearer …`. No token: `401`. Another role: `403`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors come back `success: false` with a `message` you can show as-is.
- **Who's recorded:** every action is signed with the signed-in admin (id and name), read from their token. You never send it.
- **Money** is naira as a JSON number with up to two decimals (kobo): `1500.00`. More than two decimals → `400`.
- **Times** are ISO-8601 UTC. **Days** (`from`, `to`) are calendar days in **Africa/Lagos**, `YYYY-MM-DD`, both inclusive.
- **Paging:** `page` (0-based, default `0`), `size` (default `20`, max `100`). Paged `data` is `{ content, page, size, totalElements, totalPages, last }`.
- **References:** rider `R-…`, driver `D-…`, booking `B-…`, trip `T-…` are the first six characters of the id, upper-case. Every filter that takes an id also takes its reference.
- **People:** any object with `riderId` / `driverId` also carries `rider` / `driver`: `{ id, ref, name, phone }`. Any object with `bookingId` carries `bookingRef`; with `routePostId`, `tripRef`.

---

## 0. The rules

| # | Rule | What the server does |
|---|---|---|
| 1 | **Every money action needs a reason.** | Missing or blank `reason` → `400`, nothing happens. |
| 2 | **Every money action is audited**: who, what, on which booking / driver / payout, how much, the state before and after, and why. | Written in the same transaction as the action; an action that fails leaves no half-record. See `GET /money/audit`. |
| 3 | **Two-person rule** (plan decision 4): a manual refund, a refund retry, a payout retry or a debt adjustment **for more than the approval threshold** (₦50,000; server setting `OPS_MONEY_APPROVAL_THRESHOLD`) is not done when asked. It waits as `PENDING_APPROVAL` until a **different** admin approves it (then it runs) or rejects it (with a reason). The admin who asked can cancel it. | Approving your own request → `400`. Approving / rejecting / cancelling something no longer waiting → `400`. At or below the threshold the action runs straight away. |
| 4 | **Never refund more than was paid.** | A refund is at most what the booking's payment still has: paid − refunded − refunds on their way − refunds waiting for approval. More → `400` with the amount still refundable. |
| 5 | **Never twice.** | Every refund on a booking takes a lock on that booking's payment first. Two admins refunding the same booking at the same moment: one goes through, the other gets `400` (only what's left is refundable). Send an `idempotencyKey` (any string, ≤100 chars, new per submit) and a resend - double click, retry after a timeout - returns the first result instead of acting again. |
| 6 | **Refunds go through the normal refund path.** | Paystack refund on the charge → a refund row the rider's app shows → the driver's share recorded as debt → the rider is told. Same as a cancellation refund. |
| 7 | **Refunded never reads more than paid.** | A **duplicate charge** sent back (the rider paid twice for one booking) is a separate charge and is shown apart (`duplicateChargeRefunded`), never as a refund of the booking's payment. See section 10. |

**Request statuses** (every action above is a *request*):

| status | meaning |
|---|---|
| `PENDING_APPROVAL` | Above the threshold. Nothing has been sent. Waits for a different admin. |
| `EXECUTING` | Running now (you will rarely see it). |
| `COMPLETED` | Done. For a refund: Paystack accepted it - money reaches the card in hours to days (Paystack's side). |
| `FAILED` | Tried and refused - `failureReason` says why. Nothing was sent. Can be retried (`actions.retry`). |
| `REJECTED` | A second admin said no (`decisionReason`). Nothing was sent. |
| `CANCELLED` | The requester withdrew it. Nothing was sent. |

---

## 1. Which call feeds which screen

| Screen / control | Call |
|---|---|
| Transactions: tabs, filters, sort, paging | `GET /money/transactions` |
| Transactions tab badges | `GET /money/transactions/counts` |
| Transaction drawer | `GET /money/transactions/{type}/{id}` |
| "Refund" dialog on a booking: how much can go back | `GET /money/bookings/{bookingId}/refundable` |
| "Refund" dialog submit | `POST /money/bookings/{bookingId}/refunds` |
| Approvals queue (badge: `status=PENDING_APPROVAL`) | `GET /money/approvals` |
| Approve / Reject / Cancel / Retry | `POST /money/approvals/{id}/approve` · `/reject` · `/cancel` · `/retry` |
| Refund failures | `GET /money/refund-failures` · `POST /money/refund-failures/{holdId}/retry` |
| Payouts: tabs, list, drawer | `GET /money/payouts` · `GET /money/payouts/counts` · `GET /money/payouts/{id}` |
| Retry a failed payout | `POST /money/payouts/{id}/retry` |
| Driver's payout account (masked) · reveal full number | `GET /money/drivers/{driverId}/payout-account` · `POST …/payout-account/reveal` |
| Driver earnings statement · debt history | `GET /money/drivers/{driverId}/earnings` · `GET /money/drivers/{driverId}/debt-entries` |
| Add a debt adjustment | `POST /money/drivers/{driverId}/debt-adjustments` |
| Finance dashboard | `GET /money/finance/summary` |
| Pricing monitor | `GET /money/pricing` |
| Money audit log | `GET /money/audit` |

---

## 2. Transactions

### `GET /money/transactions`

One list across **payments** (card charges: fares, and drivers paying back debt), **refunds** and **payouts**.

| Param | Default | Meaning |
|---|---|---|
| `type` | all | `PAYMENT` · `REFUND` · `PAYOUT`. Anything else → `400`. |
| `status` | all | PAYMENT `PENDING` `PAID` `FAILED` · REFUND `PENDING` `REFUNDED` · PAYOUT `PENDING` `PROCESSING` `PAID` `FAILED` |
| `from`, `to` | none | Lagos days, inclusive, on `occurredAt`. `from` after `to` → `400`. |
| `riderId` | none | Id or `R-…` |
| `driverId` | none | Id or `D-…` (a payment/refund's driver is the booking's driver) |
| `bookingId` | none | Id or `B-…` |
| `tripId` | none | Id or `T-…` - every booking on that trip |
| `amountMin`, `amountMax` | none | Naira, inclusive |
| `reference` | none | Part of the provider reference (Paystack charge reference / settlement id), any case |
| `sort` | `NEWEST` | `NEWEST` · `OLDEST` · `AMOUNT_DESC` · `AMOUNT_ASC` |
| `page`, `size` | `0`, `20` | |

```json
{ "content": [
  { "type": "REFUND", "id": "…", "occurredAt": "2026-11-13T10:02:11Z", "status": "REFUNDED", "amount": 1500.00,
    "reference": "T1234567890", "bookingId": "524ea3…", "bookingRef": "B-524EA3",
    "riderId": "…", "rider": { "id": "…", "ref": "R-A11CE0", "name": "Amaka Obi", "phone": "0801…" },
    "driverId": "…", "driver": { "id": "…", "ref": "D-D0C0FF", "name": "Emeka Okafor", "phone": "0805…" },
    "kind": "MANUAL", "reason": "Rider overcharged", "cardType": "visa", "cardLast4": "4081" }
], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "last": true }
```

- `occurredAt`: payment - when it was started; refund - when Paystack accepted it (or was asked, if pending); payout - Paystack's settlement date.
- `kind`: PAYMENT `BOOKING_PAYMENT` · `DEBT_REPAYMENT` (a driver paying back what they owe - `driverId` is set, `riderId` null). REFUND `CANCELLATION` (a cancelled seat) · `MANUAL` (an ops refund) · `DUPLICATE_CHARGE` (the rider was charged twice for one booking; the extra charge went straight back) · `OVERPAYMENT` (a driver paid back more than they owed). PAYOUT: null.
- `reference`: PAYMENT - the Paystack charge reference; REFUND - the charge it went back to; PAYOUT - Paystack's settlement id.

### `GET /money/transactions/counts`

Same filters (not `type`, `sort`, paging). `data`: `{ "all": 412, "payment": 300, "refund": 52, "payout": 60 }`.

### `GET /money/transactions/{type}/{id}`

`type` is `PAYMENT` · `REFUND` · `PAYOUT` (from the row).

```json
{
  "transaction": { …the row… },
  "trip": { "id": "…", "ref": "T-2F2F53", "originLabel": "Yaba", "destinationLabel": "Lekki Phase 1",
            "departureTime": "…", "bookingStatus": "COMPLETED", "seatCount": 1, "fareTotal": 500.00 },
  "booking": {
    "payment": { …the payment's row… },
    "refundable": { …as GET /money/bookings/{id}/refundable… },
    "hold": { "id": "…", "driverId": "…", "amount": 500.00, "released": true, "refunded": false, "refundDue": 0,
              "debtRecovery": 0, "refundReason": null },
    "earning": { "routePostId": "…", "gross": 500.00, "commission": 25.00, "net": 475.00, "creditedAt": "…" },
    "refunds": [ …refund rows, oldest first… ],
    "requests": [ …ops requests on this booking (section 3)… ]
  },
  "payout": null,
  "timeline": [
    { "at": "…", "type": "PAYMENT_STARTED", "amount": 500.00, "detail": "Checkout T123…", "actor": null },
    { "at": "…", "type": "PAYMENT_PAID", "amount": 500.00, "detail": "visa •••• 4081", "actor": null },
    { "at": "…", "type": "DRIVER_CREDITED", "amount": 500.00, "detail": "Net to driver 475.00 after commission 25.00", "actor": null },
    { "at": "…", "type": "REFUND_REQUESTED", "amount": 200.00, "detail": "Rider overcharged", "actor": { "id": "…", "name": "Ada Ops" } },
    { "at": "…", "type": "REFUND_EXECUTED", "amount": 200.00, "detail": "Rider overcharged", "actor": { "id": "…", "name": "Ada Ops" } },
    { "at": "…", "type": "REFUND_REFUNDED_MANUAL", "amount": 200.00, "detail": "Rider overcharged", "actor": null }
  ]
}
```

- `hold` is the fare's ledger: `released` = credited to the driver on completion; `refunded` = refunded on cancellation; `refundDue` > 0 = a cancellation refund Paystack hasn't accepted yet (section 4).
- `trip` is null when the booking can't be found in trip-service. `booking` is null for a debt repayment; `payout` is set only for a payout (section 5's detail).
- Timeline `type`s: `PAYMENT_STARTED`, `PAYMENT_PAID`, `PAYMENT_FAILED`, `DRIVER_CREDITED`, `REFUND_<status>_<kind>`, and every audit action (section 9).

---

## 3. Manual refund and the two-person rule

### `GET /money/bookings/{bookingId}/refundable`

```json
{ "bookingId": "…", "paymentId": "…", "reference": "T123…", "paymentStatus": "PAID",
  "paid": 500.00, "refunded": 200.00, "refundPending": 0, "awaitingApproval": 0, "refundable": 300.00,
  "duplicateChargeRefunded": 0, "approvalThreshold": 50000, "riderId": "…", "driverId": "…", "fareSettled": true,
  "blockedReason": null }
```

- `refundable` is the most the next refund can be. `refunded + refundPending + awaitingApproval` never exceeds `paid`.
- `fareSettled: false` means the seat is neither completed nor cancelled yet. Refunding **all** of it then also settles the seat
  as refunded (the trip's completion won't pay the driver for it, a later cancellation won't refund it again) - but the booking
  itself stays active in the app. To end a seat that won't be travelled, **cancel the booking** (Trips & Bookings) instead.
- `blockedReason` (phase 7, B-524EA3): set when nothing may be refunded even though the figures alone would allow it;
  `refundable` is then `0` and a refund (or approving one already waiting) is refused with `400` and the same text. Today one
  case: the booking's fare hold is marked **refunded** (the booking was cancelled and its fare settled as gone back) but **no
  refund of this payment** is on record - e.g. only a duplicate-charge refund, as on B-524EA3 (₦500 paid, rider cancelled,
  ₦507.62 duplicate charge returned). → `"The fare was already returned on this booking - check Paystack before refunding"`.
  Show it instead of the refund form; nothing to warn about client-side any more.
- `404` when the booking has no payment.

### `POST /money/bookings/{bookingId}/refunds`

```json
{ "amount": 200.00, "reason": "Rider overcharged - driver took a longer route", "idempotencyKey": "9b1f…" }
```

| Field | Required | Meaning |
|---|---|---|
| `amount` | no | Naira, > 0, ≤ `refundable`. Omit to refund everything still refundable. |
| `reason` | **yes** | ≤ 500 chars. Kept on the refund and in the audit. |
| `idempotencyKey` | recommended | New per submit; a resend returns the first result. Reusing a key for another booking → `400`. |

Response `data` - a **request**:

```json
{ "id": "…", "type": "MANUAL_REFUND", "status": "COMPLETED", "amount": 200.00, "direction": null,
  "bookingId": "…", "bookingRef": "B-524EA3", "riderId": "…", "rider": {…}, "driverId": "…", "driver": {…},
  "targetId": "<payment id>", "reason": "Rider overcharged…", "needsApproval": false, "approvalThreshold": 50000,
  "requestedBy": { "id": "…", "name": "Ada Ops" }, "requestedAt": "…",
  "decidedBy": null, "decisionReason": null, "decidedAt": null, "executedAt": "…",
  "failureReason": null, "refundId": "…", "attempts": 1,
  "actions": { "approve": false, "reject": false, "cancel": false, "retry": false } }
```

`message`: `"Done"` · `"Above the approval threshold - waiting for a second admin"` · `"Failed - <failureReason>"`.

- **At or below the threshold** it runs now: `COMPLETED` (Paystack accepted), or `FAILED` (`"Paystack did not accept the refund - nothing was sent. Retry it from Refund failures."`). A `FAILED` refund writes nothing and claims nothing; `refundable` is unchanged.
- **Above the threshold** it comes back `PENDING_APPROVAL` and nothing is sent. Its amount is held back from `refundable` meanwhile.
- `actions` are for **the admin looking at it**: `approve` / `reject` only for someone other than the requester; `cancel` only for the requester; `retry` on `FAILED`.

Errors (`400` unless said): no/blank reason · amount ≤ 0 or more than 2 decimals · amount > refundable (`"Only ₦300 can still be refunded on this booking"`) · nothing left (`"Nothing is left to refund on this booking - ₦500 paid, ₦500 already refunded or awaiting approval"`) · payment not `PAID` · `404` no payment · `409` an identical submit raced this one (refresh).

### Approvals: `GET /money/approvals`

| Param | Meaning |
|---|---|
| `status` | Repeatable: `PENDING_APPROVAL` `EXECUTING` `COMPLETED` `FAILED` `REJECTED` `CANCELLED`. Default all. |
| `type` | Repeatable: `MANUAL_REFUND` `REFUND_RETRY` `PAYOUT_RETRY` `DEBT_ADJUSTMENT`. Default all. |
| `bookingId`, `driverId` | Exact id |
| `page`, `size` | |

Newest first; items are requests as above. `GET /money/approvals/{id}` - one.

### `POST /money/approvals/{id}/approve`  body `{ "reason": "optional note" }` (or no body)

A **different** admin than `requestedBy`. Re-checks everything as if the action were asked now (e.g. what's still refundable -
a seat may have been cancelled and refunded meanwhile) and runs it. Response: the request, now `COMPLETED` or `FAILED`.

- Your own request → `400 "A different admin must approve this - you asked for it"`.
- Not `PENDING_APPROVAL` (already approved, rejected, cancelled) → `400 "This request isn't waiting for approval - it is COMPLETED"`.
- No longer possible (e.g. less is refundable now) → `400` with why; the request stays `PENDING_APPROVAL` so it can be rejected.

### `POST /money/approvals/{id}/reject`  body `{ "reason": "required" }`

A different admin. → `REJECTED`, `decisionReason` set. Missing reason → `400`. The requester gets `400` ("cancel it instead").

### `POST /money/approvals/{id}/cancel`  body `{ "reason": "optional" }`

Only the requester, only while `PENDING_APPROVAL`. → `CANCELLED`. Anyone else → `400`.

### `POST /money/approvals/{id}/retry`  body `{ "reason": "required", "idempotencyKey": "optional" }`

A `FAILED` manual refund or payout retry, tried again (a debt adjustment can't be retried - record a new one). At or below the
threshold it runs now. Above it, it goes back to `PENDING_APPROVAL` **with you as the requester**, so another admin must approve
the new attempt. Not `FAILED` → `400`.

---

## 4. Refund failures

### `GET /money/refund-failures`  `?kind=CANCELLATION|STUCK|MANUAL&page&size`

```json
{ "content": [
  { "kind": "CANCELLATION", "id": "<fare hold id>", "bookingId": "…", "bookingRef": "B-…", "riderId": "…", "rider": {…},
    "driverId": "…", "driver": {…}, "amount": 1800.00, "reason": "Driver cancelled", "failedSince": "…",
    "detail": "Paystack has not accepted this refund yet - retried automatically every 15 minutes",
    "autoRetry": true, "retryable": true, "openRequestId": null, "reference": "T123…" }
], … }
```

| kind | What it is | id | Retry |
|---|---|---|---|
| `CANCELLATION` | A cancelled seat's refund Paystack refused or couldn't be reached for. The server retries it every 15 minutes. | fare hold | `POST /money/refund-failures/{id}/retry` |
| `STUCK` | A refund `PENDING` for over an hour with nothing retrying it (e.g. the original charge can't be refunded). | refund | None here - settle it on the Paystack dashboard, then record any driver correction (section 6). |
| `MANUAL` | An ops refund Paystack refused. | request | `POST /money/approvals/{id}/retry` |

`openRequestId`: a hand retry of this one is already waiting for approval (`retryable` is then false).

### `POST /money/refund-failures/{holdId}/retry`  body `{ "reason": "required", "idempotencyKey": "optional" }`

Asks Paystack again now, through the same retry the 15-minute job runs. Two-person rule above the threshold
(`PENDING_APPROVAL`, approved as in section 3). Response: a `REFUND_RETRY` request - `COMPLETED` (refund went through, the rider's
pending refund is now refunded) or `FAILED` (`"Paystack still did not accept the refund - it stays on the automatic retry"`, or
`"Paystack could not refund this charge - settle it by hand on the Paystack dashboard"`).
Errors: no reason `400`; already refunded `400 "This refund isn't waiting on a retry - it has already gone through"`; a retry
already waiting `400`; unknown id `404`.

---

## 5. Payouts

A payout is one Paystack settlement from the driver's subaccount to their bank, synced from Paystack every 6 hours.

### `GET /money/payouts`

Params: `status` (`PENDING` · `PROCESSING` · `PAID` · `FAILED`; else `400`), `driverId` (id or `D-…`), `from`, `to` (Lagos days, on
the settlement date), `amountMin`, `amountMax`, `reference` (settlement id), `sort`, `page`, `size`.

```json
{ "content": [ { "id": "…", "driverId": "…", "driver": {…}, "amount": 4750.00, "status": "FAILED",
                 "settlementDate": "…", "reference": "<Paystack settlement id>", "lastSyncedAt": "…",
                 "retryable": true, "openRetryRequestId": null } ], … }
```

### `GET /money/payouts/counts`  `?driverId&from&to`

`{ "all": 60, "pending": 2, "processing": 1, "paid": 55, "failed": 2 }`

### `GET /money/payouts/{id}`

```json
{ "payout": { …as a list row… },
  "account": { …as GET /money/drivers/{driverId}/payout-account… },
  "retries": [ …PAYOUT_RETRY requests, oldest first… ],
  "timeline": [ { "at": "…", "type": "FIRST_SEEN", … }, { "at": "…", "type": "SETTLEMENT_FAILED", … },
                { "at": "…", "type": "PAYOUT_RETRY_REQUESTED", "actor": {…}, … } ] }
```

### `POST /money/payouts/{id}/retry`  body `{ "reason": "required", "idempotencyKey": "optional" }`

**Paystack has no "settle again" call.** It re-attempts a failed settlement itself once the bank account is good. So a retry:
1. checks the driver's default payout account with the bank through Paystack - the account must still exist and the bank's
   name for it must match the saved name;
2. re-reads the driver's settlements from Paystack now (instead of waiting up to 6 hours);
3. records that ops retried it.

`COMPLETED` = the account checks out and Paystack will re-attempt on its next settlement cycle; the payout row moves to
`PAID` when Paystack reports it. `FAILED` = the account doesn't check out (`failureReason`, e.g. "The bank now names this account
'X', not 'Y' - ask the driver to re-add it"); the driver must fix it in the app. Two-person rule above the threshold.
Errors: not `FAILED` → `400 "Only a failed payout can be retried - this one is PAID"`; a retry already waiting `400`; no reason `400`; `404`.

### `GET /money/drivers/{driverId}/payout-account`

```json
{ "driverId": "…", "hasAccount": true, "canReceivePayouts": true,
  "accounts": [ { "id": "…", "bankName": "GTBank", "bankCode": "058", "accountName": "EMEKA OKAFOR",
                  "accountNumberMasked": "••••••6789", "accountNumber": null, "isDefault": true, "linkedToPaystack": true,
                  "subaccountCode": "ACCT_…", "splitPercentage": 5.00, "settlementSchedule": "auto", "addedAt": "…" } ] }
```

`accountName` is what the bank returned when the driver saved the account - show it prominently. `canReceivePayouts`: the
default account is linked to a Paystack subaccount (otherwise the driver can't be booked).

### `POST /money/drivers/{driverId}/payout-account/reveal`  body `{ "recipientId": "optional - default account", "reason": "required" }`

Returns one account with `accountNumber` filled in, plus `revealedAt` and `expiresInSeconds: 60` - stop showing the number
then. Audited as `PAYOUT_ACCOUNT_REVEALED` with the **driver** as the target (`target.id` = `target.driverId` = the driver;
the account is `after.recipientId`, `after.shownAs` the masked number). No reason → `400` "Say why you need to see it - it
goes in the audit log"; unknown account → `404`; over the reveal rate limit (30/hour per admin, shared with ID reveals) →
`429`. Same rules as the ID reveal - see [privacy.md](privacy.md).

---

## 6. Driver earnings and settlement debt

### `GET /money/drivers/{driverId}/earnings`  `?from&to` (default the last 30 Lagos days)

```json
{ "driverId": "…", "driver": {…},
  "lifetime": { "gross": 182000.00, "commission": 9100.00, "net": 172900.00 },
  "period": { "from": "2026-10-15", "to": "2026-11-13", "gross": 24000.00, "commission": 1200.00, "net": 22800.00,
              "refundsChargedToDriver": 1140.00, "opsAdjustments": -500.00 },
  "owed": { "amount": 640.00, "limit": 10000, "blockedFromPosting": false, "minimumToUnblock": 0 },
  "payouts": { "paid": 170000.00, "inProgress": 4750.00, "failed": 0, "lastPaidAt": "…" },
  "commissionRatePercent": 5.00 }
```

`gross` / `commission` / `net` are trips credited (completed seats, and late-cancellation fees the driver kept). `owed` is the
settlement debt now. There is no "owed to driver" balance - CoRyd never holds driver money.

### `GET /money/drivers/{driverId}/debt-entries`  `?page&size`

The driver's own debt history (the same list their app shows), newest first:
`{ "id", "type": "REFUNDED_TRIP | RECOVERED_FROM_FARE | PAID_BY_CARD | OPS_ADJUSTMENT", "amount": 950.00 (signed: + adds, − pays down), "balanceAfter", "bookingId", "reference", "note", "occurredAt" }`.

### `POST /money/drivers/{driverId}/debt-adjustments`

```json
{ "direction": "DEBIT", "amount": 1500.00, "reason": "Refunded by hand on the Paystack dashboard", "idempotencyKey": "…" }
```

`DEBIT` adds to what the driver owes (e.g. a refund made by hand on Paystack). `CREDIT` takes it down (e.g. they paid in cash);
never below zero (`400 "This driver only owes ₦640 - a credit can't take it below zero"`). Written as an `OPS_ADJUSTMENT`
entry in the driver's debt ledger with your reason as its note and `reference` `ops:<request id>`. Two-person rule above the
threshold. Errors: missing `direction` / `amount` / `reason` → `400`; bad direction → `400`.

---

## 7. Finance dashboard: `GET /money/finance/summary`  `?from&to`

Default the last 30 Lagos days; at most 366 days; `from` after `to` → `400`.

```json
{ "from": "2026-10-15", "to": "2026-11-13", "timezone": "Africa/Lagos",
  "grossBookings": { "count": 300, "amount": 450000.00 },
  "refunds": { "count": 20, "amount": 30000.00 },
  "duplicateChargesRefunded": { "count": 1, "amount": 507.62 },
  "netCollected": 420000.00,
  "commission": { "tripGross": 400000.00, "commission": 20000.00, "driverNet": 380000.00 },
  "debtRepaid": { "count": 3, "amount": 9000.00 },
  "payouts": { "paid": { "count": 55, "amount": 360000.00 }, "failed": { "count": 2, "amount": 9500.00 },
               "pendingNow": { "count": 3, "amount": 14250.00 } },
  "driverDebt": { "outstandingNow": 12640.00, "driversOwingNow": 4 },
  "failures": { "failedPayments": 12, "failedPayouts": 2, "refundsAwaitingRetry": { "count": 1, "amount": 1800.00 },
                "failedManualRefunds": 0, "approvalsPending": 1 },
  "daily": [ { "date": "2026-10-15", "grossBookings": 15000.00, "payments": 10, "refunds": 0, "commission": 700.00, "payouts": 12000.00 } ] }
```

- `grossBookings`: booking payments that succeeded, by when they succeeded. `refunds`: refunds of booking payments (cancellations and ops refunds) that went through. `netCollected = grossBookings − refunds`.
- `commission`: CoRyd's share of trips credited to drivers in the window (by credit time - a trip paid last month and completed today counts today).
- `…Now` figures are point-in-time, not windowed. `daily` has one entry per Lagos day in the window, zero-filled.

---

## 8. Pricing monitor: `GET /money/pricing`  `?from&to&corridor&outliersOnly`

Routes **departing** in the window (default last 30 Lagos days; drafts excluded). A **corridor** is the normalised
`origin->destination` labels pair the suggested-fare job buckets fares on. `corridor` filters by part of that key (any case);
`outliersOnly=true` lists only corridors with an outlier.

```json
{ "from": "…", "to": "…",
  "totals": { "routes": 120, "corridors": 18, "corridorsWithRange": 11, "outliers": 6, "riderFareReports": 2 },
  "corridors": [ { "corridorKey": "yaba->lekki phase 1", "originLabel": "Yaba", "destinationLabel": "Lekki Phase 1",
                   "routes": 14, "fareMin": 1000.00, "fareMedian": 2000.00, "fareAvg": 2214.29, "fareMax": 5000.00,
                   "suggestedMin": 1500.00, "suggestedMax": 2500.00, "sampleSize": 12, "outliers": 2, "riderFareReports": 1 } ],
  "outliers": [ { "routePostId": "…", "tripRef": "T-…", "driverId": "…", "driver": {…}, "corridorKey": "…", "originLabel": "…",
                  "destinationLabel": "…", "departureTime": "…", "status": "COMPLETED", "farePerSeat": 5000.00,
                  "suggestedMin": 1500.00, "suggestedMax": 2500.00, "direction": "ABOVE", "deviationPercent": 100.0 } ],
  "riderFareReports": [ { "id": "…", "referenceNumber": "ISS-00000001", "bookingId": "…", "bookingRef": "B-…", "routePostId": "…",
                          "tripRef": "T-…", "riderId": "…", "rider": {…}, "corridorKey": "…", "description": "Charged far too much",
                          "status": "OPEN", "reportedAt": "…" } ] }
```

- `suggestedMin/Max` null: too few fares posted on that corridor for a suggested range yet; its routes can't be outliers.
- An **outlier** is a fare per seat below `suggestedMin` or above `suggestedMax`; `deviationPercent` is how far past the nearer bound. Worst first, at most 200.
- `riderFareReports`: riders' issue reports in the `PAYMENT` category on seats of these routes (newest first, at most 200).

---

## 9. Audit: `GET /money/audit`  `?targetId&actorId&action&page&size`

`targetId` matches the booking / fare hold / settlement / driver / payout account id, or a request id. Newest first:
`{ "id", "actorId", "actorName", "action", "targetType": "BOOKING|FARE_HOLD|SETTLEMENT|DRIVER|REQUEST|PAYOUT_ACCOUNT", "targetId", "requestId", "amount", "beforeState": "<JSON>", "afterState": "<JSON>", "reason", "createdAt" }`.

Actions: `REFUND_REQUESTED`, `REFUND_EXECUTED`, `REFUND_FAILED`, `REFUND_RETRY_REQUESTED`, `REFUND_RETRIED`, `REFUND_RETRY_FAILED`,
`PAYOUT_RETRY_REQUESTED`, `PAYOUT_RETRIED`, `PAYOUT_RETRY_FAILED`, `DEBT_ADJUSTMENT_REQUESTED`, `DEBT_ADJUSTED`,
`REQUEST_APPROVED`, `REQUEST_REJECTED`, `REQUEST_CANCELLED`, `REQUEST_RESUBMITTED`, `PAYOUT_ACCOUNT_REVEALED`.
A refund's `beforeState` / `afterState` are the booking's refundable figures either side of it.

---

## 10. Why B-524EA3 showed ₦507.62 refunded against ₦500 paid

The ₦507.62 was a **`DUPLICATE_CHARGE` refund**, not a refund of the ₦500 payment.

- 507.62 = 500 ÷ 0.985, rounded up to the kobo: the ₦500 fare **plus Paystack's 1.5% card fee passed on to the customer**. The only
  code path that can record a booking refund for more than the fare is a `charge.success` arriving for a reference the booking no longer
  holds while the booking is already `PAID` (`PaymentService.adoptOrphanCharge`) - the rider was charged a second time for the
  same booking, and that whole charge (Paystack's own `data.amount`, fee included) was sent straight back.
- The console's booking payment (`/internal/ops/payments/bookings`) summed **every** refund on the booking - duplicate included -
  against `amountPaid`, which counts only the booking's `PAID` payment row (₦500). A second charge sent back in full was shown
  as if it were a refund of the first.
- Not a kobo/naira slip (×100 would be ₦50,762) and not a double refund of the ₦500 (that would be ₦1,000).

**Fixed (this branch):** booking payments and every money screen count only refunds **of that payment** (`CANCELLATION`
and ops refunds) against it, capped at what was paid; duplicate-charge refunds are shown apart as `duplicateChargeRefunded`.
`refunded` can no longer read more than `amountPaid`, whatever the rows say.

Still worth checking on dev (payment-service work in progress outside this branch): whether B-524EA3's `PAID` row is on a
charge Paystack says **failed** - then the ₦507.62 was the rider's only real payment, it went back as a "duplicate", and the
booking ended up paid with no money taken. That case is what the in-progress `verifyCharge` change in `PaymentService` /
`EarningsService` addresses (keep the orphan charge as the payment when the recorded one never succeeded).

---

## 11. What the mobile apps see

| Ops action | Rider app | Driver app |
|---|---|---|
| Manual refund (completed) | Push "Refund issued - You've been refunded ₦X for a cancelled booking." (the existing refund copy). Payment history: a refund row, `status: REFUNDED`, `refundKind: CANCELLATION` (the app only knows `CANCELLATION` / `DUPLICATE_CHARGE`). Money on the card in hours to days. | Push "A trip you were paid for was refunded - ₦Y will be taken from your next earnings…" (Y = 95% of the refund). Debt history: a `REFUNDED_TRIP` entry. Over ₦10,000 owed: can't post until they pay. |
| Manual refund `PENDING_APPROVAL` / `REJECTED` / `CANCELLED` / `FAILED` | Nothing - no money moved. | Nothing. |
| Full manual refund on a seat not yet travelled | As above; **the booking stays active** in the app. Use Cancel booking to end the seat. | Not credited for the seat when the trip completes. |
| Refund retry (completed) | The pending refund in payment history turns `REFUNDED`; push "Refund issued". | Push as for a refunded trip; `REFUNDED_TRIP` debt entry. |
| Payout retry | Nothing. | Nothing at once. The payout row in Earnings turns `SUCCESS` when Paystack reports it settled. If the retry failed because of the account, the driver must re-add their bank account (Payout Setup). |
| Payout account reveal | Nothing. | Nothing. |
| Debt adjustment | Nothing. | No push. "What you owe" and the debt history update on next load: an `OPS_ADJUSTMENT` entry with your reason as its note (the driver can read it). Crossing ₦10,000 owed blocks posting (or unblocks, on a credit). |

---

## 12. Server settings

| Setting | Default | |
|---|---|---|
| `OPS_MONEY_APPROVAL_THRESHOLD` (payment-service) | `50000` | Naira. Strictly above needs a second admin. |
| `OPS_MONEY_STUCK_REFUND_MINUTES` (payment-service) | `60` | When a pending refund with no automatic retry is listed as `STUCK`. |

Database: payment-service changesets `coryd:110` (`payment.ops_money_requests`), `coryd:111` (`payment.ops_money_audit`),
`coryd:112` (time indexes for the list and the dashboard). trip-service: none (the pricing monitor reads existing tables).

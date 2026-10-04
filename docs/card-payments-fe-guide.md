# Card payments, saved cards and driver earnings: FE guide

The CoRyd wallet is gone. Riders pay every trip by card, and later bookings can use a saved card in one tap. Refunds go back to the card. Drivers don't withdraw anything: Paystack pays their share straight to their bank every week, and the app just shows them what was earned and paid.

All paths below sit under `https://api-dev.coryd.ng`. They use the normal `Authorization: Bearer …` token and the standard `{ success, message, data }` envelope.

---

## What to remove from the app

| Remove | Why |
|---|---|
| Rider wallet screen: balance, top-up, wallet history | `/payment/wallets/**` no longer exists |
| "Pay from wallet" option at checkout | `paymentMethod: "WALLET"` now returns `400`: *"Paying from a wallet is no longer available - please pay by card"* |
| Driver "Withdraw" button and withdrawal receipt | `/payment/payouts/**` no longer exists |
| Driver wallet feed (`/payment/wallets/drivers/{id}/transactions`) | Replaced by the earnings trip list and payouts list in section 3 |
| `availableBalance` on the driver summary | Removed. We don't hold driver money any more |

---

## 1. Paying for a booking: `POST /payment/payments/initiate`

```json
{
  "bookingId": "…",
  "savedCardId": "…",
  "riderEmail": "rider@example.com",
  "callbackUrl": "coryd://payment-return"
}
```

- **New card:** leave out `savedCardId`. The response has a Paystack checkout URL to open.
- **Saved card:** send `savedCardId` from section 2. We charge the card straight away, with no redirect.
- `paymentMethod` isn't needed any more. If you send it, use `PAYSTACK`.
- The amount always comes from the booking. Any `amount` you send is ignored.

How to handle the response:

| Response | What to do |
|---|---|
| `status: "PAID"`, `authorizationUrl: null` | The saved card was charged. Show success straight away |
| `status: "PENDING"` with `authorizationUrl` | Open the URL. This covers a new card, a saved card whose bank wants an OTP, and a saved card that was declined (we fall back to a normal checkout). The booking confirms when Paystack tells us, same as before |

A declined saved card is **not** an error. The rider just gets the checkout page and can pay with another card. Paying again while a checkout is still open returns that same checkout.

**The driver hasn't set up payouts:** `400` *"This driver has not finished setting up payouts, so this trip cannot be booked yet"*. Treat it as a problem with the route, not the payment. Retrying won't help.

---

## 2. Saved cards (rider)

A card is saved automatically after any successful card payment, if Paystack says it can be charged again. Paying with the same card twice keeps one entry.

### `GET /payment/cards`

```json
[
  { "id": "…", "cardType": "visa", "last4": "4081", "expMonth": "12", "expYear": "2030", "bank": "Test Bank", "expired": false }
]
```

Newest first. Show `cardType` and `last4`, and grey out cards where `expired` is `true`. The list is always the signed-in rider's own cards.

### `DELETE /payment/cards/{cardId}`

Removes the card. Using a card id that isn't the rider's own returns `404`.

---

## 3. Refunds

Every refund goes back to the card the rider paid with. Banks take **a few hours to a few working days** to show it. In the app, say *"Refunded to your card - allow a few working days"*, never "check your wallet".

For a late cancellation (25% fee), the rider gets 75% back to their card. The push on refund (`REFUND_ISSUED`) works as before.

---

## 4. Driver earnings

The driver's share of each fare goes to their Paystack account the moment the rider pays. Paystack then pays it into their bank **once a week**. Drivers can't request early payout, and nothing is held by CoRyd. A driver can only see their own earnings (ops can see anyone's).

### Header: `GET /payment/earnings/drivers/{driverId}/summary`

```json
{
  "lifetimeGross": 125000.00,
  "lifetimeNet": 118750.00,
  "todayNet": 2375.00,
  "earnedSinceLastPayout": 9500.00,
  "paidToBank": 109250.00,
  "payoutInProgress": 0.00,
  "lastPayoutAt": "2026-10-02T09:00:00Z",
  "settlementDebt": 0.00
}
```

| Field | Show as |
|---|---|
| `todayNet`, `lifetimeNet` | Earned today, earned in total, after commission |
| `earnedSinceLastPayout` | **"Earned since your last payout"**: on its way in the next weekly payout |
| `paidToBank` | **"Paid to your bank"**: total Paystack has paid out |
| `payoutInProgress` | A payout Paystack has started but not finished. Show it only when it's above 0 |
| `lastPayoutAt` | "Last payout on …". `null` before the first payout |
| `settlementDebt` | Money the driver owes back after a refund we paid for them. Show it plainly when above 0; their next fares pay it off |

`paidToBank` and `payoutInProgress` come from Paystack's records, which we check every few hours, so a payout can take a little while to show up. `earnedSinceLastPayout` is our own figure for trips completed since the last payout. It's a close guide, but Paystack's payout amount is the final word (Paystack's fees and refunds can make it differ slightly).

### Period card: `GET /payment/earnings/drivers/{driverId}/summary/period?period=WEEK`

`period` is `TODAY`, `WEEK` or `MONTH`. With `MONTH`, `month=2026-08` picks a past month. The response is `{ grossEarned, commission, netEarned }`. **`netToWallet` has been renamed to `netEarned`.**

### Trip list: `GET /payment/earnings/drivers/{driverId}/trips?period=WEEK&page=0&size=20`

The same `period` and `month` as the card, in the standard paged shape. Each item:

```json
{
  "id": "…", "bookingId": "…", "routePostId": "…", "occurredAt": "…",
  "grossFare": 1200.00, "commissionAmount": 60.00, "netCredit": 1140.00,
  "rider": { "riderId": "…", "firstName": "Ada", "lastName": "Obi", "photoUrl": "…" }
}
```

A row is a completed trip, or a late-cancellation fee the driver kept. `routePostId` opens the trip receipt. If the rider's name can't be loaded it comes back `null`; show a dash.

### Payouts list: `GET /payment/earnings/drivers/{driverId}/payouts?page=0&size=20`

```json
{ "id": "…", "amount": 9500.00, "status": "SUCCESS", "settlementDate": "2026-10-02T09:00:00Z" }
```

`status` is `PENDING`, `PROCESSING`, `SUCCESS` (in the bank) or `FAILED`. Payouts still in progress, which have no date yet, come first, then newest first.

### Removed driver endpoints

- `GET /payment/earnings/drivers/{driverId}`: use `/summary`.
- `GET /payment/earnings/drivers/{driverId}/transactions`: use `/trips`.

# Bank account before posting: FE guide

## 1. Posting eligibility: `GET /trip/routes/drivers/me/posting-eligibility`

A driver can't post a route until they have saved the bank account their earnings go to.

- `blockedReason: "PAYOUT_ACCOUNT_REQUIRED"` comes with `canPostRoutes: false`. Send the driver to Add bank account, the same way you handle `IDENTITY_REQUIRED` and `VEHICLE_REQUIRED`.
- New field: `payoutAccountOk` (boolean), next to `identityOk` and `vehicleOk`. Use it for the checklist tick.
- The check applies to approved drivers too, not only drivers who are still onboarding.
- Order: identity and vehicle reasons come first. `PAYOUT_ACCOUNT_REQUIRED` shows only when those are done.
- `blockedReason: "AMOUNT_OWED"`: the driver owes more than ₦10,000 from refunded trips. New field `amountOwed` holds the amount (it's present whenever it's known, even below the limit). Show it with a **Pay now** button; see "Owed from refunded trips" in card-payments-fe-guide.md. Posting a route in this state returns `400` naming the amount.
- When the driver saves an account, call eligibility again. It clears straight away.

Posting a route without a bank account returns `400`: `Add the bank account your earnings will be paid to before posting a route`.

## 2. Saved bank accounts: `GET /payment/payout-recipients/drivers/{driverId}`

This endpoint already exists. Use it to show the driver's saved accounts on any device.

```json
[
  {
    "id": "…",
    "bankName": "GTBank",
    "bankCode": "058",
    "accountNumber": "0123456789",
    "accountName": "ADA OBI",
    "default": true,
    "createdAt": "…"
  }
]
```

- Newest first. `"default": true` marks the account payouts go to. An empty list means no account is saved yet.
- Only the driver can read their own accounts. Anyone else gets an error.
- Every account in the list was confirmed with the bank before it was saved, so show them all as verified.
- To switch the payout account: `PUT /payment/payout-recipients/drivers/{driverId}/{recipientId}/select`.

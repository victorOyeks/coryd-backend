# Prembly Driver Verification — Setup

Prembly is how a driver's licence gets checked against FRSC and a plate number against the vehicle
register. It lives in `profile-service` and is **off until you give it a key**.

---

## What it does

| Check | Endpoint | Triggered by | On a match |
|---|---|---|---|
| Driver's licence | `POST /verification/drivers_license/advance/v2` | `PUT /profile/drivers/me` (licence number changed) | `GOVERNMENT_ID` auto-approves |
| Plate number | `POST /verification/vehicle` | Registering or editing a vehicle | Recorded only — ops still approves the vehicle |

The plate record names a car, not an owner, so it cannot show that this driver may drive it. That
is why it informs the decision instead of making it.

**Nothing here ever rejects a driver.** A name that does not match, a licence FRSC has never heard
of, a timeout, a rate limit, an expired key — every one of them leaves the item exactly where it
was and writes the reason to `verification_checks` for ops. If Prembly is down, onboarding carries
on working exactly as it did before this existed.

---

## Getting set up

1. Create an account at [prembly.com](https://prembly.com) and complete their business KYC. They
   ask for CAC documents — Kinetum Global Solutions Ltd, RC 9825799.
2. Take the **test** secret key from the dashboard first. It answers with fixed test data, so the
   whole flow can be exercised without spending anything.
3. Fund the wallet and swap in the live key once the test flow is right. Prembly bills per
   successful lookup, and the per-check caching below is what keeps that bill sane.

Test and live share the same host, `https://api.prembly.com` — **the key is the only difference**.
There is no separate sandbox URL to point at, and no base-url change when you go live. The flip
side is that a live key in a staging `.env` will bill you for real, so treat the key as the
environment switch it actually is.

```bash
PREMBLY_ENABLED=true
PREMBLY_BASE_URL=https://api.prembly.com        # same for test and live
PREMBLY_API_KEY=<secret key>
PREMBLY_NAME_MATCH_THRESHOLD=0.85
PREMBLY_CROSS_CHECK_VEHICLE=true
```

The key goes in `.env`, which is gitignored. It must never reach the repo, a Slack message or a
screenshot.

### Sandbox test data

| Check | Value |
|---|---|
| Plate number | `AAA000000` → returns a blue Toyota |

Prembly publishes licence test values in the dashboard; they change per account, so read them there
rather than hardcoding one here.

---

## The name match

FRSC returns names uppercased, sometimes with the middle name attached to the first name, and
Nigerian names are routinely spelt more than one way. Exact equality would reject honest drivers,
so `NameMatcher` scores first and last names separately with Jaro-Winkler and takes **the weaker of
the two** — a matching surname cannot carry a first name that is nothing like the record.

`PREMBLY_NAME_MATCH_THRESHOLD` is the bar, 0–1, default `0.85`.

- **Raise it** if wrong people are getting through. Costs you ops time.
- **Lower it** if real drivers are being sent to manual review. Costs you certainty.

Watch the `nameMatchScore` on `NAME_MISMATCH` rows for the first few weeks before touching it — the
distribution of real near-misses tells you where the bar belongs far better than a guess does.

This is also why names are stored as `first_name` / `last_name` rather than one field: FRSC matches
on a surname, and a single name column can only ever be compared whole.

---

## Cost control

One row per `(driver, check type, licence-or-plate)`, unique-constrained. A check that returned an
answer is never repeated — only a changed licence number or plate buys another lookup, and only a
`PROVIDER_ERROR` is retried, because that one told us nothing.

So re-saving a profile is free. Correcting a typo in a licence number is one more lookup, which is
correct: it is a different licence.

---

## Operating it

Checks run off the request thread on a 2–4 thread pool with a bounded queue, so a driver saving
their profile never waits on FRSC. If the queue overflows the check is dropped — the item simply
stays pending for ops, which is where an unchecked item belongs anyway.

`GET /profile/drivers/{driverId}/verification-status` returns `providerChecks`, the evidence behind
an automatic approval and the reason behind anything sent to manual review.

**Turning it off** is `PREMBLY_ENABLED=false` and a restart. Every driver then goes to ops, exactly
as before. Reach for this if Prembly starts returning wrong matches — it is a safer failure than
auto-approving the wrong people.

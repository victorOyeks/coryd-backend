# Ops Console: Privacy - masked IDs and the audited reveal

Phase 7 (PRD §22, NDPA 2023): government ID numbers, dates of birth and bank details are **masked by default** in every
ops response, and the full value is shown only on a **deliberate, audited reveal**.

**Basics:** base URL `https://api-dev.coryd.ng/ops`; `OPS_ADMIN` token; usual envelope `{ responseId, timestamp, success, message, data }`.

---

## 1. How masked values read

| Value | Field(s) in ops responses | Masked as | Example |
|---|---|---|---|
| NIN | `ninMasked` (and `ninLast4`, kept) | four bullets, a space, the last four digits | `"•••• 5040"` |
| Driver's licence number | `licenceNumberMasked` | a bullet per hidden character, then the last four | `"•••••••5678"` |
| Date of birth | `birthYear` | the year only (a number) | `1990` |
| Bank account number | `accountNumberMasked` (money) | a bullet per hidden digit, then the last four | `"••••••6789"` |

The bullet is `•` (U+2022). Text the providers wrote (Smile ID summaries, decision notes quoting them) is masked the
same way: `"Licence number on the card is ••••5678, driver entered ••••5679"`, and dates of birth there read as years
(`"NIMC and licence dates of birth differ (born 1990 / 1991)"`). Rows written before phase 7 were rewritten by coryd:151.

### Where they appear (full values removed)

| Endpoint | Before | Now |
|---|---|---|
| `GET /drivers/{id}/verification` (and the approve / reject / save / item / resubmission responses) | `driver.licenceNumber`, `driver.dateOfBirth` | `driver.licenceNumberMasked`, `driver.birthYear` |
| `GET /drivers/{id}/verification/licence` | `licence.licenceNumber`, `licence.dateOfBirth` | `licence.licenceNumberMasked`, `licence.birthYear` |
| `GET /identity-verification/people`, `/people/{id}`, the identity queue | `ninLast4` | `ninLast4` + `ninMasked`; `verifiedRecord.dateOfBirth` → `verifiedRecord.birthYear` |
| profile-service's ops profile (`/internal/users/{id}/ops-profile`, behind `GET /drivers/{id}`) | `driver.licenceNumber`, `driver.dateOfBirth` | `driver.licenceNumberMasked`, `driver.birthYear` (the console profile didn't show either) |
| `GET /money/drivers/{id}/payout-account` | `accountNumberMasked: "******6789"` | `accountNumberMasked: "••••••6789"` |

ops-service also masks anything profile-service passes through (`licenceNumber`, `dateOfBirth`, `idNumber`, `nin` never leave
ops-service in full), so an older profile-service mid-deploy can't leak them. The driver's own app is unchanged: a driver
still sees their own licence number and date of birth.

---

## 2. `POST /users/{userId}/reveal`

```json
{ "field": "NIN" | "LICENCE_NUMBER" | "DATE_OF_BIRTH", "reason": "Rider disputes the Smile ID match" }
```

**200**
```json
{ "field": "NIN", "value": "12345675040", "source": "NIMC_RECORD", "revealedAt": "2026-11-13T10:00:00Z", "expiresInSeconds": 60 }
```

- `value` is the full value. **Stop showing it after `expiresInSeconds` (60)** and don't keep it (no local storage, no logs).
- `source`: `PROFILE` (what the driver entered) or `NIMC_RECORD` (the NIN result from Smile ID).
- `LICENCE_NUMBER`: the number the driver entered (the one checked against the licence).
- `DATE_OF_BIRTH`: the driver's profile date of birth; for a rider (or a driver without one), the date on their NIMC record.
- `NIN`: **CoRyd keeps only the last four digits** (and a fingerprint to spot reuse). The full number is returned only when
  the latest Smile ID NIN result on file carries it and it ends in those four digits. Otherwise `404` "CoRyd keeps only the
  last four digits of this NIN (•••• 5040) - look the job up in the Smile ID portal if you need the full number".

| Status | When |
|---|---|
| `400` `Say why you need to see it - it goes in the audit log` | `reason` missing or blank (over 500 characters → `400` too) |
| `400` `field must be one of [NIN, LICENCE_NUMBER, DATE_OF_BIRTH]` | unknown `field` (case doesn't matter) |
| `403` `Your ops role (FINANCE) can't reveal ID numbers or dates of birth - ask a verification or support admin` | role without the permission |
| `404` | nothing of that kind on file for this person (nothing is recorded) |
| `429` `You've revealed 30 details in the last hour - try again in N minutes` | rate limit (§4) |

**Permission:** `VERIFICATION_DECIDE` **or** `ACCOUNT_MANAGE` - super admins, verification and support. A rule in
`OpsAccessPolicy` (`POST /users/*/reveal`) sits before the generic `/users/**` → `ACCOUNT_MANAGE` rule.

## 3. Audit

Every reveal is recorded **before** the value is returned, by the service that owns the data, and shows in `GET /audit`:

| | ID reveal | Bank account reveal |
|---|---|---|
| Kept by | profile-service `pii_reveal_log` (coryd:150) | payment-service `ops_money_audit` |
| `category` | `PRIVACY` | `MONEY` |
| `action` | `PII_REVEALED` | `PAYOUT_ACCOUNT_REVEALED` |
| `target` | `ACCOUNT`, the person's user id | `PAYOUT_ACCOUNT`, the **driver's** id (`target.driverId` too) |
| `subject` | `NIN`, `Driver's licence number`, `Date of birth` | - |
| `after` | `{field, shownAs}` - the masked value | `{driverId, recipientId, field: "BANK_ACCOUNT_NUMBER", shownAs}` |
| `reason`, `actor` | as given / the admin | as given / the admin |

The full value is never stored in the audit. Filter with `category=PRIVACY`, `action=PII_REVEALED`, or `targetType=ACCOUNT&targetId=…`
(a person's trail now includes reveals of their details).

## 4. Rate limit

At most **30 reveals per admin in any rolling hour** (`ops.privacy.reveals-per-hour`), ID and bank reveals together. Over it:
`429` with when the next one is allowed. A reveal that fails (bad input, `404`, the owning service down) doesn't count.
Counted in memory per ops-service instance (one instance today) - a restart resets it.

## 5. Bank account reveal (money)

`POST /money/drivers/{driverId}/payout-account/reveal` `{ recipientId?, reason }` - path unchanged. Now consistent with §2:
same `400` message for a missing reason, same rate limit, the response adds `revealedAt` and `expiresInSeconds: 60`, and the
audit names the driver as the target (older reveals were moved onto the driver by coryd:152). See [money.md](money.md).

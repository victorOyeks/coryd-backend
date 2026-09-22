# Driver Documents & Grace Period — FE Implementation Guide

Base path `/trip` · port `9103` · profile endpoints under `/profile` · port `9102`

This covers driver onboarding and the "upload your papers later" flow: a driver tells us who they
are and what they drive, waits for that to be approved, then starts posting routes — and is nudged
to upload their insurance and roadworthiness certificate before a deadline.

**If you built against an earlier draft, three things changed:**

1. A driver can no longer post before completing the identity and vehicle steps
2. Those steps must now be **approved by ops**, not just submitted — so onboarding ends in a
   waiting state, and `IDENTITY_PENDING_APPROVAL` / `VEHICLE_PENDING_APPROVAL` need a real screen
3. The eligibility response carries `blockedReason` and `prerequisiteLevel`

Read it top to bottom.

---

## The one idea you need before anything else

**A new driver can post routes as soon as we know who they are and what they drive — but their
insurance and roadworthiness certificate can wait.**

Previously a driver was blocked until ops *approved* five separate items. There is no ops dashboard
yet, so in practice *no real driver could ever post a route*.

The gate is now split in two:

| Required before the first route | Deferred to the grace window |
|---|---|
| Driver's licence — image, licence number, date of birth | Insurance certificate |
| Vehicle — make, model, year, colour, plate number | Roadworthiness certificate |

**These must be approved by ops, not merely uploaded.** A driver who submits everything still
cannot post until a human reviews it — so the onboarding flow ends in a waiting state, not in a
Post Route screen.

The strictness is server config, reported as `prerequisiteLevel`:

- **`APPROVED`** (current default) — ops must approve the licence and the vehicle first
- **`SUBMITTED`** — uploaded/registered is enough, no ops in the loop

Read `prerequisiteLevel` from the API and let `blockedReason` drive the screen; don't hardcode
either behaviour, because this can change without an app release.

For the deferred documents:

- A driver posts their **first route** → a **30-day countdown starts at that moment**
- During those 30 days they operate normally
- When it runs out, **posting is blocked again** until their documents are approved
- An approved driver is never affected by any of this

The countdown starts on the **first route post**, not on signup. A driver who signs up and does
nothing for two months still gets a full 30 days when they finally post.

The window length and whether it's enforced at all are **server config** (`GRACE` / `STRICT` / `OFF`,
default `GRACE` / 30 days). Never hardcode 30 in the app — read it from the API.

---

## The one endpoint that drives everything

```
GET /trip/routes/drivers/me/posting-eligibility     ← authenticated, driver role
```

```json
{
  "data": {
    "canPostRoutes": true,
    "documentsApproved": false,
    "identityOk": true,
    "vehicleOk": true,
    "blockedReason": null,
    "prerequisiteLevel": "APPROVED",
    "enforcement": "GRACE",
    "gracePeriodDays": 30,
    "firstRouteAt": "2026-09-16T08:12:44Z",
    "uploadDeadline": "2026-10-16T08:12:44Z",
    "daysRemaining": 21
  }
}
```

| Field | Meaning |
|---|---|
| `canPostRoutes` | Whether posting will succeed right now. **Drive the Post Route button off this.** |
| `documentsApproved` | Fully approved on all four items. When `true`, ignore everything else here. |
| `identityOk` | The driver's licence meets the current `prerequisiteLevel` |
| `vehicleOk` | Vehicle meets the current `prerequisiteLevel` |
| `prerequisiteLevel` | `SUBMITTED` or `APPROVED` — what "required" currently means |
| `blockedReason` | Why posting is blocked, or `null`. See the table below |
| `enforcement` | `GRACE`, `STRICT` or `OFF` — server policy, may change without an app release |
| `gracePeriodDays` | Window length in days |
| `firstRouteAt` | When the countdown started. `null` if they have never posted — the clock hasn't started |
| `uploadDeadline` | When posting stops. `null` if approved, or if the clock hasn't started |
| `daysRemaining` | Whole days left, floored, never negative. `null` if approved or not started |

It's self-scoped — there is no `{driverId}` variant, and a driver can only ever see their own.

### `blockedReason` values

| Value | Meaning | Send them to |
|---|---|---|
| `null` | Not blocked | — |
| `IDENTITY_REQUIRED` | No driver's licence on file | Driver's licence upload |
| `VEHICLE_REQUIRED` | Identity done, no vehicle registered | Add vehicle (make, model, colour, plate) |
| `IDENTITY_PENDING_APPROVAL` | Uploaded, awaiting ops review *(APPROVED level only)* | "Under review" screen — nothing for them to do |
| `VEHICLE_PENDING_APPROVAL` | Registered, awaiting ops review *(APPROVED level only)* | "Under review" screen |
| `DOCUMENTS_OVERDUE` | Grace window closed | Insurance + roadworthiness upload |
| `AWAITING_APPROVAL` | Server is in `STRICT` mode — ops must approve | "Under review" screen |

### The states to design for

| State | Looks like | UI |
|---|---|---|
| **Approved** | `documentsApproved: true` | Nothing. No prompt, no banner. |
| **Needs identity** | `canPostRoutes: false`, `IDENTITY_REQUIRED` | Blocking onboarding screen. This is the new-driver path. |
| **Needs vehicle** | `canPostRoutes: false`, `VEHICLE_REQUIRED` | Blocking "add your vehicle" screen. |
| **Awaiting review** | `canPostRoutes: false`, `*_PENDING_APPROVAL` | "We're reviewing your details" — no action button, they just wait |
| **Clock not started** | `canPostRoutes: true`, `firstRouteAt: null` | Post Route enabled. No countdown yet. |
| **In grace** | `canPostRoutes: true`, `daysRemaining: 21` | Post Route enabled + countdown prompt. |
| **Expired** | `canPostRoutes: false`, `DOCUMENTS_OVERDUE` | Post Route disabled, hard blocking screen. |

---

## First-run order for a new driver

There are two distinct mechanisms and they need different UI. Don't collapse them into one "upload
documents" screen.

**1. Blocking gate — identity and vehicle.** A brand-new driver cannot post until these are
uploaded **and approved**. No skip, no "remind me later".

```
Driver signs up / adds DRIVER role
  → GET posting-eligibility
  → IDENTITY_REQUIRED          → driver's licence upload              (blocking)
  → VEHICLE_REQUIRED           → add vehicle: make, model, colour, plate (blocking)
  → IDENTITY_PENDING_APPROVAL  → "we're reviewing" — nothing to do    (blocking)
  → VEHICLE_PENDING_APPROVAL   → "we're reviewing" — nothing to do    (blocking)
  → canPostRoutes: true        → Post Route unlocked, clock not yet started
```

**Expect drivers to sit in `*_PENDING_APPROVAL` for hours or days.** That screen is not an edge
case — every single driver passes through it. Give it real design: what's been submitted, what's
still under review, and a support contact. Do not show a retry or re-upload button; there is
nothing for them to fix.

**2. Deferrable nudge — insurance and roadworthiness.** Once posting is unlocked, these can wait.
This is where "skip and set up later" belongs, and where the countdown applies.

Order matters: ask for identity and vehicle **during onboarding**, before the driver ever reaches an
empty Post Route screen. A driver who taps Post and only then discovers they must upload an ID will
mostly abandon.

---

## Prompt cadence

Deliberately **your call, client-side** — the backend gives you state and a deadline, not a schedule.
Store "last dismissed" locally.

This section is about the **deferrable** documents only — insurance and roadworthiness. The identity
and vehicle steps are a blocking gate and are never "prompted".

Suggested cadence:

- On entering driver mode, if `daysRemaining` is set and not dismissed in the last 7 days
- Escalate to **daily** when `daysRemaining <= 7`
- Make it **non-dismissible** when `blockedReason` is `DOCUMENTS_OVERDUE`

Name the actual documents — "your documents" makes drivers hunt for what's missing:

> **21 days to upload your insurance and roadworthiness certificate**
> Upload both to keep posting routes after 16 October.
> *[Upload now]* *[Remind me later]*

And when it expires:

> **Upload your insurance and roadworthiness certificate**
> Your 30-day window has closed. Upload both to start posting routes again.
> *[Upload now]*

---

## What to ask them to upload

```
GET /profile/drivers/me/onboarding-status     ← authenticated
```

```json
{
  "data": {
    "profileSubmitted": true,
    "governmentIdSubmitted": true,
    "vehicleSubmitted": true,
    "documentsSubmitted": false,
    "overallStatus": "PENDING"
  }
}
```

These are **submitted**, not **approved** — a submitted-but-unreviewed item reads `true` here,
because the next move is ops's, not the driver's. Use it to build the checklist of what's still
missing so the prompt names the actual gap rather than saying "upload your documents".

For the approve/reject detail, including rejection reasons:

```
GET /profile/drivers/{driverId}/verification-status     ← authenticated
```

> **Changed:** this used to be readable by anyone authenticated. It's now **self or OPS_ADMIN only**;
> passing another driver's id returns 403. Pass the signed-in driver's own id.

---

## Error handling on Post Route

`POST /trip/routes/drivers/{driverId}` can now fail with **400** and one of these:

```
Upload your government ID before posting a route
Register your vehicle - make, model, colour and plate number - before posting a route
Your government ID is still being reviewed - you can post routes once it is approved
Your vehicle is still being reviewed - you can post routes once it is approved
Your document upload window has closed - upload your insurance and
roadworthiness certificate to post routes again
```

Treat it as a **state refresh trigger**, not a generic error toast: re-fetch
`posting-eligibility`, then show the blocking screen. The window can close between a screen loading
and the driver tapping Post, so don't assume your cached `canPostRoutes` is still true.

The older `"Driver/vehicle is not yet verified - cannot post a route (D-104)"` message still exists
but only appears when the server is in `STRICT` mode. Handle both by falling back to the
eligibility refresh.

---

## Rider side: the "verified driver" badge

`driverVerified` on booking details, trip receipts and search results now means **fully approved on
all four items** — it is `false` for every driver operating under grace.

Previously it reflected the government ID only, which would have put a green "verified" tick on
a driver who had uploaded no vehicle papers at all. That would have been actively misleading once
unverified drivers started carrying passengers, so it was changed.

Expect `driverVerified: false` to be **common** now, not exceptional. Design for it:

- Show a positive badge only when `true` (e.g. ✓ Verified)
- When `false`, show **nothing** or a neutral "New driver" — not a warning. These are legitimate
  drivers within their window, and a red flag on most of your supply will hurt conversion

---

## Checklist before you ship

- [ ] Post Route button state comes from `canPostRoutes`, never from a local guess
- [ ] `blockedReason` routes to the right screen — including the two `*_PENDING_APPROVAL` states
- [ ] An "awaiting review" screen exists with no action button
- [ ] Identity and vehicle are collected during onboarding, not on first Post Route tap
- [ ] The blocking gate has no skip; only the insurance/roadworthiness nudge is dismissible
- [ ] `gracePeriodDays` and `uploadDeadline` are read from the API — 30 is not hardcoded
- [ ] `firstRouteAt: null` is handled (clock not started — don't show "0 days left")
- [ ] `daysRemaining: null` is handled (approved, or not started)
- [ ] The 400 on post triggers an eligibility refresh, not just a toast
- [ ] Prompt escalates inside 7 days and becomes non-dismissible at 0
- [ ] `verification-status` is called with the signed-in driver's own id
- [ ] Rider UI treats `driverVerified: false` as normal, not as a warning

---

## Backend notes (for context)

- Enforcement is `DRIVER_VERIFICATION_ENFORCEMENT` (`GRACE` default) and
  `DRIVER_VERIFICATION_GRACE_DAYS` (30). Changing them needs no app release.
- Switching to `STRICT` once the ops dashboard exists will **immediately block every driver who
  isn't approved**, including ones mid-grace. That needs a comms plan, not just a config flip.
- Drivers operating without approved documents, and those whose window closes within 7 days, now
  appear in the daily ops report.

---

## Approving a driver (ops, until the dashboard exists)

`DRIVER_PREREQUISITE_LEVEL=APPROVED` means **no driver can post until someone approves them**.
There is no ops UI yet, so approvals are API calls made with an `OPS_ADMIN` token.

**1. Find who's waiting**

```bash
curl "$GATEWAY/profile/drivers/pending-verification" \
  -H "Authorization: Bearer $OPS_TOKEN"
```

**2. Review one driver**

```bash
curl "$GATEWAY/profile/drivers/{driverId}/verification-status" \
  -H "Authorization: Bearer $OPS_TOKEN"
```

Returns the licence image, the vehicle fields and both photos, every item's status, and
`providerChecks` — what FRSC and the plate register said (see below).

**3. Decide — all items in one call**

```bash
curl -X PATCH "$GATEWAY/profile/drivers/{driverId}/verification" \
  -H "Authorization: Bearer $OPS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "decisions": [
      { "item": "GOVERNMENT_ID", "status": "APPROVED" },
      { "item": "VEHICLE",       "status": "REJECTED", "rejectionReason": "Plate number not legible" }
    ],
    "reviewedBy": "victor@coryd.ng"
  }'
```

Approving `GOVERNMENT_ID` and `VEHICLE` unblocks posting. `INSURANCE` and `ROADWORTHINESS_CERT`
can wait for the grace window, but all four must be approved before the driver counts as fully
verified and riders see the verified badge.

### You need an OPS_ADMIN account in production

`OPS_ADMIN` cannot be self-assigned — `AuthService` blocks it on signup and on `roles/add`. The
seeded ops admin (`08000000003`) only exists under the `dev` and `test` profiles.

**Before running `APPROVED` in production you must create one**, by inserting the `OPS_ADMIN` role
directly for a chosen user. Without it, nobody can approve anyone and every driver is blocked
permanently.

---

## What changed in this release

Three things land together and all of them touch your request bodies. Ship them together.

### 1. The selfie is gone

`SELFIE` is no longer a reviewable item. Drop the capture screen, drop `selfieUrl` /
`selfiePublicId` from the profile payload, and drop `selfieSubmitted` from the onboarding
checklist — the server no longer accepts or returns any of them. Identity now rests on the
driver's licence alone, which is checked against FRSC (below).

### 2. Names are two fields, everywhere

`legalName` and `fullName` are gone from every request and response. Send and read `firstName` and
`lastName` instead.

| Where | Was | Now |
|---|---|---|
| `PUT /profile/drivers/me` | `legalName` | `firstName` + `lastName`, both required |
| `PUT /profile/riders/me` | `fullName` | `firstName` (required) + `lastName` |
| Driver/rider summaries, search results, chat, booking manifest | `legalName` / `fullName` | `firstName` + `lastName` |
| Login / refresh response | `fullName` | `fullName` **kept** (still first name only) + `firstName` + `lastName` |
| Wallet feed `rider` block | `fullName` | `firstName` + `lastName` |

The login response deliberately keeps its `fullName` key so nothing breaks the moment the backend
deploys — it still carries the first name only, as before. Move to `firstName` at your own pace.

Join the two yourself for display. The split exists because FRSC matches on a surname, and a single
name field can only ever be compared whole.

**Existing accounts** are migrated automatically: first word becomes the first name, **last** word
becomes the surname. Middle names are dropped. Users with a middle name they care about will need
to re-enter it, so don't be surprised by support tickets.

### 3. The driver's licence is verified automatically

`PUT /profile/drivers/me` now **requires** two fields that used to be optional:

| Field | Why |
|---|---|
| `idNumber` | The driver's licence number — what FRSC is queried with |
| `dateOfBirth` | Required alongside it |

And `POST /profile/drivers/{driverId}/vehicles` now requires `color`, which is cross-checked
against the plate register.

Expect **400** on a save that omits any of these. Make all four fields required in the form.

#### What happens after the save

The check runs in the background — the save returns immediately and never fails because of it.
Then, within a few seconds:

- **Licence matches the name on the profile** → `GOVERNMENT_ID` flips to `APPROVED` on its own, no
  ops involved. The driver moves straight to `VEHICLE_PENDING_APPROVAL`.
- **Anything else** — name too different, licence not found, FRSC down, rate limited → the item
  **stays exactly where it was** and goes to ops with a note. It is never auto-rejected.

So `IDENTITY_PENDING_APPROVAL` is now often over in seconds rather than days. Poll
`posting-eligibility` once shortly after a licence save, or let the driver pull-to-refresh —
don't assume the state you got back from the save is final.

#### `providerChecks` on the verification status

`GET /profile/drivers/{driverId}/verification-status` gains a `providerChecks` array. It is
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
| `MATCH` | Confirmed. For a licence, this is what auto-approved the item |
| `NAME_MISMATCH` | Record exists, name is too far off — ops decides |
| `DETAILS_MISMATCH` | Plate found, declared make or colour contradicts the register |
| `NOT_FOUND` | No such licence or plate |
| `PROVIDER_ERROR` | Provider unreachable — our problem, not the driver's |

The driver app can show `MATCH` as a small reassurance ("Licence confirmed"). **Do not surface the
other outcomes to the driver.** They are ops-facing: a driver seeing "name mismatch" will assume
rejection, and a `PROVIDER_ERROR` is us, not them.

A plate `MATCH` does **not** approve the vehicle. The register names a car, not an owner, so it
cannot show the driver is entitled to drive it — ops still reviews every vehicle.

The array is empty when the provider is switched off, which is the default until the live key is
issued. Treat empty as "no information", not as a failure.

---

## `IN_REVIEW`: a fourth verification status

`VerificationStatus` was `PENDING | APPROVED | REJECTED`, and `PENDING` carried two different
meanings that the app had to tell apart by itself:

- nothing has been submitted — **the driver has work to do**
- submitted, no ops verdict yet — **the driver can only wait**

There is now an explicit `IN_REVIEW` for the second, so nothing has to be inferred:

| Status | Means | Whose turn |
|---|---|---|
| `PENDING` | Never submitted | **Driver** |
| `IN_REVIEW` | Submitted, sitting with ops | **Ops** — show a wait state, no action button |
| `APPROVED` | Cleared | — |
| `REJECTED` | Turned down, `rejectionReason` is set | **Driver** |
| `EXPIRED` | Was approved, then lapsed. `rejectionReason` names the document and date | **Driver** |

This applies everywhere a status appears: `governmentId.status`, `vehicleRegistration.status`,
each entry in `documents[]`, `overallStatus`, and `verificationStatus` on driver summaries in
search results and booking screens.

### What changes for you

**Treat `IN_REVIEW` as a wait state, never as a prompt.** The mistake to avoid is rendering it the
same as `PENDING` — that puts an "Upload your insurance" button in front of a driver who uploaded
it an hour ago.

**`overallStatus` now distinguishes the two as well.** It is `PENDING` while *anything* is still
unsubmitted, and only becomes `IN_REVIEW` once everything has been submitted and the whole file is
with ops. So `overallStatus: "IN_REVIEW"` is the "you're done, we're checking" screen.

**Nothing about the posting gate changes.** `canPostRoutes` and `blockedReason` behave exactly as
before, and `IDENTITY_PENDING_APPROVAL` / `VEHICLE_PENDING_APPROVAL` keep their names — those
already made the distinction, which is why they exist.

**Expired documents get their own status.** A lapsed certificate reads `EXPIRED`, not `PENDING` —
the driver has done this before and only needs a current copy, so the prompt should say "renew",
not "upload". The vehicle is suspended alongside it and also reads `EXPIRED`, with a
`rejectionReason` naming the document that caused it. Re-uploading returns both to `IN_REVIEW`.

Note the expiry job runs nightly, so a certificate that lapses today flips overnight rather than
at the stroke of midnight.

`overallStatus` reports `EXPIRED` ahead of `PENDING` and `IN_REVIEW`, since a lapsed document is
the most urgent thing outstanding.

> **Deploy note:** this is a shared enum. Older clients that switch exhaustively on the three old
> values will fall through on `IN_REVIEW` — make the mapping tolerant of unknown values before
> this ships.

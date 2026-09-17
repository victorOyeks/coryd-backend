# Driver Documents & Grace Period — FE Implementation Guide

Base path `/trip` · port `9103` · profile endpoints under `/profile` · port `9102`

This covers driver onboarding and the new "upload your papers later" flow: a driver tells us who
they are and what they drive, starts posting routes that same session, and gets nudged to upload
their insurance and roadworthiness certificate before a deadline.

**If you built against an earlier draft of this guide, two things changed:** a new driver can no
longer post before completing identity and vehicle steps, and the eligibility response now carries
a `blockedReason`. Read it top to bottom.

---

## The one idea you need before anything else

**A new driver can post routes as soon as we know who they are and what they drive — but their
insurance and roadworthiness certificate can wait.**

Previously a driver was blocked until ops *approved* five separate items. There is no ops dashboard
yet, so in practice *no real driver could ever post a route*.

The gate is now split in two:

| Required before the first route | Deferred to the grace window |
|---|---|
| Government ID **uploaded** | Insurance certificate |
| Selfie **uploaded** | Roadworthiness certificate |
| Vehicle registered — make, model, colour, plate number | |

Note **uploaded, not approved** — nothing needs ops in the loop, so these are enforceable today.

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
    "identitySubmitted": true,
    "vehicleRegistered": true,
    "blockedReason": null,
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
| `documentsApproved` | Fully approved on all five items. When `true`, ignore everything else here. |
| `identitySubmitted` | Government ID **and** selfie uploaded |
| `vehicleRegistered` | A vehicle with make, model, colour and plate number is on file |
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
| `IDENTITY_REQUIRED` | No government ID and/or selfie on file | ID + selfie upload |
| `VEHICLE_REQUIRED` | Identity done, no vehicle registered | Add vehicle (make, model, colour, plate) |
| `DOCUMENTS_OVERDUE` | Grace window closed | Insurance + roadworthiness upload |
| `AWAITING_APPROVAL` | Server is in `STRICT` mode — ops must approve | "Under review" screen |

### The states to design for

| State | Looks like | UI |
|---|---|---|
| **Approved** | `documentsApproved: true` | Nothing. No prompt, no banner. |
| **Needs identity** | `canPostRoutes: false`, `IDENTITY_REQUIRED` | Blocking onboarding screen. This is the new-driver path. |
| **Needs vehicle** | `canPostRoutes: false`, `VEHICLE_REQUIRED` | Blocking "add your vehicle" screen. |
| **Clock not started** | `canPostRoutes: true`, `firstRouteAt: null` | Post Route enabled. No countdown yet. |
| **In grace** | `canPostRoutes: true`, `daysRemaining: 21` | Post Route enabled + countdown prompt. |
| **Expired** | `canPostRoutes: false`, `DOCUMENTS_OVERDUE` | Post Route disabled, hard blocking screen. |

---

## First-run order for a new driver

There are two distinct mechanisms and they need different UI. Don't collapse them into one "upload
documents" screen.

**1. Blocking gate — identity and vehicle.** A brand-new driver cannot post at all until these are
done. `canPostRoutes` is `false` and `blockedReason` is `IDENTITY_REQUIRED` or `VEHICLE_REQUIRED`.
This is a required onboarding step, not a prompt — no skip, no "remind me later".

```
Driver signs up / adds DRIVER role
  → GET posting-eligibility
  → IDENTITY_REQUIRED  → government ID + selfie upload   (blocking)
  → VEHICLE_REQUIRED   → add vehicle: make, model, colour, plate  (blocking)
  → canPostRoutes: true → Post Route unlocked, clock not yet started
```

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
    "selfieSubmitted": false,
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
Upload your government ID and selfie before posting a route
Register your vehicle - make, model, colour and plate number - before posting a route
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
all five items** — it is `false` for every driver operating under grace.

Previously it reflected government ID + selfie only, which would have put a green "verified" tick on
a driver who had uploaded no vehicle papers at all. That would have been actively misleading once
unverified drivers started carrying passengers, so it was changed.

Expect `driverVerified: false` to be **common** now, not exceptional. Design for it:

- Show a positive badge only when `true` (e.g. ✓ Verified)
- When `false`, show **nothing** or a neutral "New driver" — not a warning. These are legitimate
  drivers within their window, and a red flag on most of your supply will hurt conversion

---

## Checklist before you ship

- [ ] Post Route button state comes from `canPostRoutes`, never from a local guess
- [ ] `blockedReason` routes to the right screen — identity, vehicle, or documents
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

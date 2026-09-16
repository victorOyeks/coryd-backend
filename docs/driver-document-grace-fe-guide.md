# Driver Documents & Grace Period — FE Implementation Guide

Base path `/trip` · port `9103` · profile endpoints under `/profile` · port `9102`

This covers the new "skip document upload for now" flow: a driver signs up, starts posting routes
immediately, and gets nudged to upload their vehicle documents before a deadline. Read it top to
bottom — the deadline behaviour is the part that will surprise you.

---

## The one idea you need before anything else

**A new driver can post routes straight away, but only for a limited window.**

Previously a driver was blocked from posting until ops approved six separate items (government ID,
selfie, vehicle, insurance, roadworthiness). There is no ops dashboard yet, so in practice *no real
driver could ever post a route*.

That gate is now relaxed, not removed:

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
| `documentsApproved` | Fully approved on all six items. When `true`, ignore everything else here. |
| `enforcement` | `GRACE`, `STRICT` or `OFF` — server policy, may change without an app release |
| `gracePeriodDays` | Window length in days |
| `firstRouteAt` | When the countdown started. `null` if they have never posted — the clock hasn't started |
| `uploadDeadline` | When posting stops. `null` if approved, or if the clock hasn't started |
| `daysRemaining` | Whole days left, floored, never negative. `null` if approved or not started |

It's self-scoped — there is no `{driverId}` variant, and a driver can only ever see their own.

### The four states to design for

| State | Looks like | UI |
|---|---|---|
| **Approved** | `documentsApproved: true` | Nothing. No prompt, no banner. |
| **Not started** | `canPostRoutes: true`, `firstRouteAt: null` | Post Route enabled. Soft "complete your profile" nudge at most. |
| **In grace** | `canPostRoutes: true`, `daysRemaining: 21` | Post Route enabled + countdown prompt. |
| **Expired** | `canPostRoutes: false`, `daysRemaining: 0` | Post Route disabled, hard blocking screen. |

---

## Prompt cadence

Deliberately **your call, client-side** — the backend gives you state and a deadline, not a schedule.
Store "last dismissed" locally.

Suggested cadence:

- On entering driver mode, if `daysRemaining <= gracePeriodDays` and not dismissed in the last 7 days
- Escalate to **daily** when `daysRemaining <= 7`
- Make it **non-dismissible** when `canPostRoutes` is `false`

Keep the copy about the deadline, not about compliance:

> **21 days to upload your documents**
> Upload your vehicle papers to keep posting routes after 16 October.
> *[Upload now]* *[Remind me later]*

And when it expires:

> **Upload your documents to post routes**
> Your 30-day window has closed. Once your vehicle papers are approved you can post routes again.
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

`POST /trip/routes/drivers/{driverId}` can now fail with **400** and this message:

```
Your document upload window has closed - upload and get your vehicle
documents approved to post routes again
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
all six items** — it is `false` for every driver operating under grace.

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

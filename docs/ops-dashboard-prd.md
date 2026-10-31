# CoRyd Ops Console — Product & Functional Requirements

**Owner:** Victor Oyekanmi · **For:** design and engineering · **Status:** draft for design
**System:** CoRyd, operated by Kinetum Global Solutions Ltd (RC 9825799)

---

## 1. Why this exists

CoRyd runs today with no administrative interface at all. Every operational action — approving a
driver, resolving an SOS, refunding a rider — is either impossible or requires a developer running
API calls by hand.

This is now blocking the business, not just inconvenient:

- **Drivers cannot be approved.** The route-posting gate requires approved identity and vehicle.
  Approval only happens through an API call carrying an `OPS_ADMIN` token. No console, no
  approvals, no supply.
- **SOS alerts have no destination.** An `SosIncident` is recorded with an `opsNotifiedAt` field
  and a Kafka event is published. Nobody is watching.
- **Issue reports go nowhere.** Riders and drivers can file them, with `OPEN` / `IN_REVIEW` /
  `RESOLVED` states. Nothing can move them out of `OPEN`.
- **Refunds, payouts and disputes** have no manual path.

The console's job is to let one or two non-technical people run the whole platform end to end.

### Success criteria

| Measure | Target |
|---|---|
| Driver submission → approval decision | Under 4 working hours |
| SOS alert → ops acknowledgement | Under 2 minutes |
| Issue report → first response | Under 24 hours |
| Operational actions needing a developer | Zero |

---

## 2. Users

**Ops Admin** — the only role that exists (`OPS_ADMIN`). Non-technical. Reviews drivers, handles
incidents and support, issues refunds. Works on a laptop. Today this is the two founders.

**Finance / Founder** — same account, different intent. Wants revenue, commission, payouts and
unsettled liability. Reads rather than acts.

> **Design for one role.** The backend has no partial permissions. Role separation is a future
> phase and should not shape the information architecture now.

---

## 3. Scope

Sixteen modules, covering every entity in the system.

| # | Module | Backend today |
|---|---|---|
| 1 | Overview | Stats endpoints exist |
| 2 | Driver verification queue | **Exists** |
| 3 | Drivers | Partial |
| 4 | Riders | Partial |
| 5 | Routes & trips | Partial |
| 6 | Bookings | Partial |
| 7 | Payments | Partial |
| 8 | Refunds | Event only |
| 9 | Driver earnings & payouts | Partial |
| 10 | ~~Wallets~~ (removed - card-only payments) | n/a |
| 11 | Safety / SOS | **Missing** |
| 12 | Support / issues | **Missing** |
| 13 | Ratings & quality | Partial |
| 14 | Configuration | **Missing** |
| 15 | Notifications & delivery | Partial |
| 16 | Reports | **Exists** |
| 17 | Admin users & audit | **Missing** |
| 18 | System health | **Missing** |

"Missing" means the data exists but there is no endpoint. Those are listed in §21.

---

## 4. Module 1 — Overview

The landing screen. Answers "is anything on fire, and how did yesterday go?"

**US-1.1** As an ops admin, I want to see anything needing action immediately, so that nothing
urgent waits behind routine work.

*Acceptance criteria*
- Action tiles, each with a count and a link: drivers awaiting approval, active SOS incidents,
  open issue reports, failed payouts, grace windows expiring within 7 days
- Any active SOS is visually dominant over everything else on the page
- A tile with a zero count is muted, not hidden — absence is information

**US-1.2** As a founder, I want yesterday's trading summary, so that I can see the business without
opening a report.

*Acceptance criteria*
- Gross fares, commission, seats booked, routes posted, each with a day-on-day delta
- Same figures as the daily email, so the two never disagree
- Date range selector: today, yesterday, 7 days, 30 days, custom

**US-1.3** As a founder, I want marketplace health at a glance, so I can tell whether supply or
demand is the constraint.

*Acceptance criteria*
- Seat fill rate, trip completion rate, active drivers vs approved drivers, repeat rider rate

*Data available:* `GET /trip/internal/stats`, `/payment/internal/stats`, `/identity/internal/stats`,
`/profile/internal/stats`, all accepting `from` and `to`.

---

## 5. Module 2 — Driver verification queue

**The highest-priority module.** Until it ships, no driver can work.

### Background the designer needs

Five items are independently reviewable per driver:

```
GOVERNMENT_ID · SELFIE · VEHICLE · INSURANCE · ROADWORTHINESS_CERT
```

**Three block earning** — `GOVERNMENT_ID`, `SELFIE`, `VEHICLE`. Without all three approved the
driver cannot post a route at all.

**Two do not** — `INSURANCE` and `ROADWORTHINESS_CERT` sit inside a 30-day grace window that starts
at the driver's first posted route.

Overall status is all-or-nothing: any item `REJECTED` makes the driver `REJECTED`; all five
`APPROVED` makes them `APPROVED`; submitted-but-undecided is `IN_REVIEW`; anything still missing is `PENDING`.

**US-2.1** As an ops admin, I want a queue of drivers awaiting review, ordered by wait time, so the
longest-waiting driver is handled first.

*Acceptance criteria*
- Columns: driver name, which items are pending, time waiting, whether any pending item is blocking
- Default sort: oldest first
- Filters: blocking only, item type, resubmissions, expiring documents
- Visually separate **blocking** from **non-blocking** rows — a driver waiting on insurance is not
  losing money; one waiting on their ID is
- Empty queue is the success state and should read as such

**US-2.2** As an ops admin, I want to review one driver on a single screen, so I can decide without
navigating.

*Acceptance criteria*
- Driver's licence image, zoomable, shown beside the FRSC result for that licence
- Vehicle exterior and interior photos, zoomable
- Vehicle details: make, model, year, colour, plate number, seat capacity, AC declared/confirmed
- Per-item status, previous rejection reason if any, and submission timestamp
- `providerChecks`: what FRSC and the plate register said, with the name-match score. A
  `NAME_MISMATCH` must show both names side by side — that comparison *is* the decision
- Keyboard navigation to the next driver without returning to the list

**US-2.3** As an ops admin, I want to approve or reject all items in one action, so review is fast.

*Acceptance criteria*
- **Approve all** is a single click with no confirmation dialog
- Rejection is per-item and requires a reason
- Canned rejection reasons plus free text — the reason is shown to the driver in their app
- One submission per driver, not one per item
- On submit, the next driver loads automatically

**US-2.4** As an ops admin, I want to see items that returned to the queue automatically, so I don't
mistake them for new submissions.

*Acceptance criteria*
- Documents past their expiry date are flipped to `EXPIRED` by a nightly job, with a
  system-generated reason, with no human involved. They do not enter the review queue - the next
  move is the driver's re-upload, and they reappear as `IN_REVIEW` once it lands
- These are labelled distinctly from a driver's fresh submission

### Three system behaviours that constrain the design

1. **Approving an item permanently deletes its image.** Licence, vehicle photos and documents are
   purged from storage on approval for privacy, and can never be displayed again.
2. **Resubmission resets status to `IN_REVIEW`.** Any edit before approval does the same, so it returns to the queue.
3. **`verifiedSince` is set once, on first-ever approval, and never moves** — it is a "verified
   since" date, not a last-reviewed date.
4. **A licence that matches FRSC approves itself.** Those drivers never reach this queue, so what
   ops sees is the exceptions: mismatches, unknown licences, and everything the provider could not
   answer. Design the queue for that population, not for the average driver.
5. **A failed provider check never rejects anyone.** `PROVIDER_ERROR` means the provider was
   unreachable, which is our problem — the row must not read as a strike against the driver.

*API:* `GET /profile/drivers/pending-verification` · `GET /profile/drivers/{driverId}/verification-status`
· `PATCH /profile/drivers/{driverId}/verification`

---

## 6. Module 3 — Drivers

**US-3.1** Search and list all drivers, by name, phone, plate number or verification status.

**US-3.2** View one driver end to end: profile, vehicle, documents, verification history, routes
posted, trips completed, ratings received, earnings to date, payout history, issues raised against
them.

**US-3.3** See a driver's posting eligibility and, where blocked, the reason — awaiting approval,
identity missing, vehicle missing, or grace window expired — with the deadline date.

**US-3.4** Suspend or reactivate a driver, with a reason, so a dangerous driver can be stopped
immediately.

**US-3.5** See the reliability score already computed from completed versus driver-cancelled trips.

> **Gap:** there is no suspend endpoint. `User.active` exists on the entity but nothing toggles it.
> See §21.

---

## 7. Module 4 — Riders

**US-4.1** Search and list riders by name or phone.

**US-4.2** View one rider: profile, booking history, saved cards (type and last 4 only), payments, refunds,
ratings given and received, emergency contacts, issues raised.

**US-4.3** Suspend or reactivate a rider, with a reason.

**US-4.4** See riders with unusual patterns — repeated cancellations, repeated no-shows, repeated
refund requests.

---

## 8. Module 5 — Routes & trips

**US-5.1** List routes with filters on status, date, driver and corridor.

**US-5.2** Watch a trip in progress on a map, with the driver's live position — the data already
streams over WebSocket for riders.

**US-5.3** See routes stuck `IN_PROGRESS` past a reasonable duration and resolve them.

*This is a live problem.* `RouteExpiryJob` logs `OPS REVIEW NEEDED` every five minutes for routes
in progress more than six hours, and deliberately will not auto-resolve them — it cannot tell
whether the trip happened and the driver forgot to tap Complete, or it never ran. **Today those log
lines are the only signal, and nobody reads them.** The console must surface this queue and let ops
complete or cancel the route.

**US-5.4** Cancel a route on a driver's behalf, with a reason, triggering the normal refund cascade.

**US-5.5** View a route's passenger manifest and per-passenger booking states.

---

## 9. Module 6 — Bookings

**US-6.1** Search bookings by reference, rider, driver or date.

**US-6.2** View a booking's full timeline — `BookingStatusHistory` already records every transition.

**US-6.3** See payment, fare hold, commission split and refund against a booking in one view.

**US-6.4** Cancel a booking on a rider's behalf, choosing whether to waive the cancellation fee.

---

## 10. Module 7 — Payments

**US-7.1** List all payment transactions, filtered by status, method, date and rider.

**US-7.2** Investigate a failed or stuck payment — gateway reference, status, and the ability to
re-query Paystack.

**US-7.3** Reconcile a day's takings against Paystack settlements.

*Note for design:* every payment is a card payment for a booking. Payments made with a saved card
look the same as checkout payments.

---

## 11. Module 8 — Refunds

**US-8.1** See every refund issued, its reason, amount, method and settlement state.

**US-8.2** Issue a manual refund against a booking, full or partial, with a reason.

**US-8.3** Show that refunds go back to the card and settle over hours to days on Paystack's side -
a refund marked issued is not yet money in the rider's account.

> **Gap:** refunds are only triggered automatically by cancellation. There is no manual refund
> endpoint.

---

## 12. Module 9 — Driver earnings & payouts

**US-9.1** View a driver's earnings: lifetime gross, lifetime commission, earned since last payout,
and any settlement debt.

**US-9.2** List a driver's Paystack payouts (next working day) (settlements) with status `PENDING`, `PROCESSING`,
`SUCCESS` or `FAILED`.

**US-9.3** See what each driver owes from refunded trips. Paystack takes every refund from CoRyd's
balance, so the driver's share of each refund is recorded against them automatically and recovered
from their next fares; over ₦10,000 they can't post until they pay it back by card. Ops can add a
manual correction (e.g. a refund made by hand in the Paystack dashboard).

**US-9.4** See total driver debt - money drivers owe back to CoRyd right now. CoRyd holds no driver
money, so there is no "owed to drivers" balance.

**US-9.5** See and verify a driver's bank details before a payout leaves.

> **Known risk to surface:** bank details are stored without any account-name verification call.
> The console should make the account name prominent so a human can sanity-check it.

---

## 13. Module 10 — Wallets (removed)

CoRyd no longer has a wallet. Riders pay every trip by card, every fare is split to the driver's
Paystack subaccount at charge time, and refunds go back to the card. **The console must not
introduce any stored balance, top-up or withdrawal**, as holding customer money would change CoRyd's
regulatory position with Paystack and the CBN.

---

## 14. Module 11 — Safety & SOS

**The most time-critical module in the product.**

**US-11.1** As an ops admin, I want an SOS alert to interrupt whatever I am doing, so I can respond
within seconds.

*Acceptance criteria*
- Active SOS overrides the current screen — audible alert, persistent banner, cannot be dismissed
  without acknowledgement
- Shows: who triggered it, the trip, live location on a map, driver and rider names and phone
  numbers, vehicle and plate, and the other passengers on that route
- One tap to call either party
- Acknowledging stamps `opsNotifiedAt`, which the entity already carries

**US-11.2** Record what was done and resolve the incident, with notes.

**US-11.3** Review historic SOS incidents.

> **Gap:** `SosIncident` is written and `coryd-sos-triggered` is published, but there is no endpoint
> to read incidents and nothing consumes the event for ops. **Today an SOS reaches nobody.**

---

## 15. Module 12 — Support & issue reports

**US-12.1** See all issue reports with status, category, reference number and age.

**US-12.2** Open a report, see the booking, both parties and the full trip context.

**US-12.3** Move a report through `OPEN` → `IN_REVIEW` → `RESOLVED`, with internal notes.

**US-12.4** Act from within a report — issue a refund, suspend a user, cancel a booking.

**US-12.5** Contact the reporter and record the response.

> **Gap:** `IssueReport` is created by users and never read back. No list, no detail, no transition
> endpoint. Every report is currently stuck at `OPEN` forever.

---

## 16. Module 13 — Ratings & quality

**US-13.1** View ratings for a driver or rider with the distribution and recent comments.

**US-13.2** See low-rated drivers, so quality problems surface before complaints do.

**US-13.3** Remove an abusive or retaliatory rating, with a reason.

**US-13.4** See fare reports — riders flagging a fare as unreasonable.

---

## 17. Module 14 — Configuration

Every one of these is currently an environment variable requiring a redeploy.

**US-14.1** View and change the **commission rate** (currently 5%).

**US-14.2** View and change the **cancellation policy** — free window (2 hours) and late fee (50%).

**US-14.3** View and change the **driver verification policy** — enforcement mode, grace period
length, and whether prerequisites need approval or only submission.

**US-14.4** View and change **suggested fare ranges** per corridor.

**US-14.5** See who changed a setting, when, and what the previous value was.

> **Design note:** changing commission or cancellation policy alters the published Terms of Service.
> The UI must warn that a change has legal and customer-facing consequences, and must not apply
> retroactively to existing bookings.

---

## 18. Module 15 — Notifications & delivery

**US-15.1** View the notification log — every SMS, WhatsApp, push and email, with delivery state.

**US-15.2** Investigate a user who says they never received an OTP.

**US-15.3** See delivery failure rates by channel, so a failing provider is noticed quickly.

**US-15.4** Resend a failed notification.

> **Important caveat for design:** `NotificationLog.delivered` currently means *the gateway accepted
> the request*, not *the message arrived*. Until delivery-receipt webhooks are wired, the column
> must be labelled "Accepted", not "Delivered", or ops will be misled during exactly the
> investigation this screen exists for.

---

## 19. Module 16 — Reports

**US-16.1** View daily, weekly and monthly reports in the console, matching the emailed versions.

**US-16.2** Trigger a report for any date or period on demand.

**US-16.3** Manage report recipients.

**US-16.4** Export any list or report to CSV, for accounting and for the auditor.

*API:* `POST /notification/admin/reports/{DAILY|WEEKLY|MONTHLY}` and `.../preview`, both accepting
an optional `date`.

---

## 20. Modules 17–18 — Admin users, audit, system health

**US-17.1** Invite and manage ops admin accounts.

**US-17.2** See an audit trail of every ops action — who approved which driver, who refunded what,
who changed a setting. `reviewedBy` is captured on verification decisions; nothing else is audited.

**US-18.1** See integration health at a glance: Paystack, Termii, WhatsApp, Brevo, Firebase.

**US-18.2** See the state of background jobs — route expiry, seat-hold expiry, document expiry,
departure reminders, suggested fares, report jobs — including last run and failures.

**US-18.3** See WhatsApp and SMS balance or quota before it runs out and silently stops sign-ups.

---

## 21. Backend work this requires

The designer should know which screens are designing against data that does not yet have an
endpoint. Engineering should treat this as the build list.

| Need | Status | Priority |
|---|---|---|
| SOS incident list, detail, acknowledge, resolve | Entity only, no API | **P0 — safety** |
| Issue report list, detail, status transition, notes | Entity only, no API | **P0** |
| Stuck `IN_PROGRESS` route queue + ops resolve | Logs only | **P0** |
| Suspend / reactivate user | `User.active` unused | P1 |
| Manual refund | Automatic only | P1 |
| Settlement debt entry | API exists, no screen | P1 |
| Driver & rider list/search/detail | Partial | P1 |
| Booking list/search + admin cancel | Partial | P1 |
| Runtime configuration + audit | Env vars only | P2 |
| Rating moderation | No endpoint | P2 |
| Ops audit log | Not captured | P2 |
| Integration & job health | Not exposed | P2 |
| `seatCapacity` in the verification payload | Missing from review data | P1 — cheap |
| Delivery receipts so "delivered" is true | Accepted-only today | P1 |

`VerificationCase` exists as an entity and repository and is entirely unused — decide whether the
console adopts it as the case record or it is deleted.

---

## 22. Non-functional requirements

**Platform.** Desktop-first, 1440px baseline. Reviewing ID photographs on a phone is not viable.
Mobile should be read-only, primarily for SOS alerting away from a desk.

**Localisation.** Naira with thousands separators throughout. **All dates and all reporting
boundaries in `Africa/Lagos`** — the backend computes every period on Lagos days, and a console on
UTC will not reconcile with the emailed reports.

**Real time.** SOS must be pushed, not polled. Verification queue and stuck-trip counts should
refresh without a manual reload.

**Performance.** Queue and list screens must stay usable at thousands of rows — server-side paging,
filtering and sorting.

**Security.** `OPS_ADMIN` only. The console exposes driver's licence images, phone numbers and
bank details, so it needs session timeout, and ideally 2FA and IP restriction before it holds real
user data.

**Privacy (NDPA 2023).** Personal data must not be displayed more widely than needed. Bank details
and government ID numbers should be masked by default and revealed deliberately, with the reveal
audited.

**Empty and error states.** Empty is common and frequently the *good* outcome. Every list needs a
designed empty state, and every action needs a designed failure state.

**Accessibility.** This is a tool people use all day: keyboard navigation throughout, visible focus,
and no colour-only status encoding.

---

## 23. Suggested phasing

**Phase 1 — unblock the business**
Overview · Driver verification queue · SOS · Issue reports · Stuck trips

Everything in Phase 1 is either blocking revenue or is a safety obligation.

**Phase 2 — day-to-day operations**
Drivers · Riders · Bookings · Payments · Refunds · Payouts · Suspension

**Phase 3 — control and insight**
Configuration · Ratings · Notifications · Reports · Audit · System health

---

## 24. Open questions

1. Should ops be able to **edit** a driver's vehicle details, or only approve/reject what was
   submitted?
2. Does suspending a driver cancel their upcoming routes and refund those riders automatically?
3. Who is legally responsible for SOS escalation, and does the console need to log that an
   emergency service was contacted?
4. Do we need a "verified account name" check on driver bank details before enabling payout retry?
5. Should config changes take effect immediately, or be scheduled with an effective date given the
   Terms implications?
6. Is there an approval threshold above which a refund needs a second person?

---

## Appendix A — Reference data

**Roles:** `RIDER`, `DRIVER`, `OPS_ADMIN`

**Booking status:** `RESERVED`, `HELD`, `CONFIRMED`, `EN_ROUTE_TO_PICKUP`, `AT_PICKUP`,
`IN_PROGRESS`, `CANCELLED_RIDER`, `CANCELLED_DRIVER`, `COMPLETED`, `NO_SHOW`

**Route status:** `DRAFT`, `PUBLISHED`, `FULL`, `IN_PROGRESS`, `CANCELLED`, `COMPLETED`

**Verification status:** `PENDING` (nothing submitted), `IN_REVIEW` (submitted, with ops), `APPROVED`, `REJECTED`, `EXPIRED` (approved then lapsed)

**Reviewable items:** `GOVERNMENT_ID`, `SELFIE`, `VEHICLE`, `INSURANCE`, `ROADWORTHINESS_CERT`

**Document types:** `INSURANCE`, `ROADWORTHINESS_CERT`

**Payment method:** `PAYSTACK` (card) · **Purpose:** `BOOKING_PAYMENT` · **Payout (settlement) status:**
`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`

**Notification channels:** SMS, WhatsApp, Push, Email

**Background jobs:** route expiry, seat-hold expiry, route departure reminders, suggested fares,
live-search session cleanup, document expiry, ops report jobs

**Services and ports:** identity 9101 · profile 9102 · trip 9103 · payment 9104 · notification 9105

---

## Appendix B — Current business rules

| Rule | Value | Source |
|---|---|---|
| Platform commission | 5% of fare, deducted from driver payout | `commission.percentage` |
| Free cancellation window | 2 hours before departure | `cancellation.free-cancellation-window-hours` |
| Late cancellation fee | Up to 50% of fare | `cancellation.late-fee-percentage` |
| OTP validity | 5 minutes | `AuthService.OTP_TTL_MINUTES` |
| Document grace period | 30 days from first route | `driver.verification.grace-period-days` |
| Prerequisite level | Approved identity + vehicle | `driver.verification.prerequisite-level` |
| Driver payout | Split to the driver's own Paystack subaccount at charge time, settled the next working day | `DriverSplitResolver` |
| Stored balances | None - card-only, refunds to the card | `PaymentService` |
| Reporting timezone | `Africa/Lagos` | `report.timezone` |

> The first three appear in the published Terms of Service at coryd.ng/terms. Changing them in the
> console changes what CoRyd has contractually promised its users.

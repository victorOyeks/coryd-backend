# First trip celebration ("first drive" / "first ride")

When a driver completes their first trip, or a rider their first ride, the app shows one short celebration screen and asks how it went. What they share lands in the ops console's **First trip stories** list.

Owner: **trip-service** (it is where trips complete). Console reads go through ops-service. Added 2026-11-20, changeset `coryd:187`.

## How "first" is decided

The server decides, so it survives a reinstall and shows once.

- When a trip completes, trip-service checks, after the commit, whether it was the person's **only** completed trip in that role:
  - **Rider:** completed bookings (`COMPLETED`) == 1.
  - **Driver:** completed routes that carried at least one rider to the end == 1. An empty route someone completes doesn't count.
- If so, it writes a `PENDING` row in `trip.first_trip_experiences` (one per user **and role**). Someone who rides and drives is celebrated once for each.
- **No backfill.** Rows are only written by completions after this shipped, so anyone who already had a completed trip never sees it.
- Every completion path counts: the driver's Complete Trip, and ops marking a stuck trip completed.
- A pending celebration is offered for 14 days after the trip, then no longer.
- Sharing or skipping ends it. Deleting the account erases the row.

## App endpoints (always the signed-in user, from the token)

`{role}` is `DRIVER` or `RIDER`.

### `GET /trip/me/first-trip/{role}` — "celebrate?"

`data` is `null` when there's nothing to celebrate, otherwise:

```json
{
  "id": "5d0c...",
  "role": "RIDER",
  "routePostId": "cf78...",
  "bookingId": "af34...",
  "originLabel": "Ikeja City Mall, Obafemi Awolowo Way, Ikeja",
  "destinationLabel": "Victoria Island, Lagos",
  "completedAt": "2026-11-20T17:30:00Z"
}
```

`bookingId` is null for a driver.

### `POST /trip/me/first-trip/{role}/share`

```json
{ "feeling": "LOVED_IT", "story": "So easy, the driver was lovely", "consentToShare": true }
```

- `feeling` (required): `LOVED_IT`, `GOOD`, `OKAY`, `NOT_GREAT`, `BAD`. Missing: `400 Pick how it went`.
- `story` (optional): up to 500 characters, trimmed; blank is stored as null. Longer: `400 Keep it under 500 characters`.
- `consentToShare` (optional, default false): CoRyd may quote it.

### `POST /trip/me/first-trip/{role}/skip`

No body.

Both return `404 There's no first trip to share yet` when there is no row, and `400 You've already answered this one - thank you!` once answered.

## App flow

- **Rider:** Trip Feedback (rate the driver) opens when the trip completes, as before. It first asks `GET …/RIDER`; when the pending `bookingId` is this booking, the celebration shows first, then the normal driver rating. The celebration asks about CoRyd and the trip; the rating stays about the driver, so nothing is asked twice.
- **Driver:** Complete Trip leads to Rate Passengers, as before. It first asks `GET …/DRIVER`; when the pending `routePostId` is this route, the celebration shows first, then passenger ratings and the trip summary.
- One screen: a confetti burst in brand colours and a light haptic, a 5-face picker, an optional "Tell us how it went" box, a "CoRyd can share this" switch (off), Submit and Skip.

## Console

`GET /ops/first-trip-stories?role=&consent=&page=&size=` (ops-service). Shared stories only (skips aren't stories), newest first, `size` up to 50.

- `role`: `DRIVER` or `RIDER` (any case); anything else is `400 role must be DRIVER or RIDER`.
- `consent`: `true` / `false`.
- **Permission:** `SAFETY_VIEW` (like ratings) or `GROWTH_MANAGE`. Read only.

Each item:

```json
{
  "id": "5d0c...", "role": "RIDER",
  "person": { "id": "...", "ref": "R-...", "role": "RIDER", "name": "Qa Driver Two", "phone": "...", "photoUrl": null },
  "routePostId": "cf78...", "tripRef": "T-...", "bookingId": "af34...", "bookingRef": "B-...",
  "originLabel": "Ikeja City Mall, ...", "destinationLabel": "Victoria Island, Lagos",
  "feeling": "LOVED_IT", "story": "So easy", "consentToShare": true,
  "completedAt": "2026-11-20T17:30:00Z", "submittedAt": "2026-11-20T17:31:12Z"
}
```

trip-service's internal source is `GET /trip/internal/ops/first-trip-stories` (same filters).

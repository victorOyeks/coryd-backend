# Ops Console: Broadcasts

Sidebar: **Notifications → Broadcasts**. Send one message to many riders and/or drivers - a road closure, a service notice,
an announcement - by push, SMS, email and (once its template is approved) WhatsApp.

**Basics:** base URL `https://api-dev.coryd.ng/ops`; `OPS_ADMIN` token (none → `401`, other role → `403`); the usual
envelope; the signed-in admin is recorded from their token; times ISO-8601 UTC; paging `page`/`size`, paged `data` is
`{ content, page, size, totalElements, totalPages, last }`.

---

## 0. The rules

| # | Rule | What the server does |
|---|---|---|
| 1 | **Nothing sends inside the request.** | Creating a broadcast only records it as `SCHEDULED`. A sender in notification-service picks it up within ~5 s (or at `sendAt`) and sends on its own thread. |
| 2 | **Rate-limited.** | At most `BROADCAST_RATE_PER_SECOND` recipients a second (default 10) across all channels, one broadcast at a time. 1,000 riders ≈ 2 minutes. |
| 3 | **Announcement opt-outs are respected.** | A recipient who turned announcements off (`promotionalOptIn = false` in their notification preferences) is `SKIPPED` - unless the broadcast is `urgent`. A recipient who turned WhatsApp off gets no WhatsApp (`OPTED_OUT`); other channels still go. |
| 4 | **Quiet hours** for non-urgent sends: 21:00–07:00 Lagos (`BROADCAST_QUIET_HOURS_START/END`). | One scheduled in quiet hours is moved to 07:00 (`deferredForQuietHours: true`, the preview warns). One still sending at 21:00 pauses and resumes at 07:00. `urgent: true` ignores quiet hours and announcement opt-outs - use it only for service/safety notices. |
| 5 | **Suspended accounts never receive one.** | profile-service leaves them out of every audience. |
| 6 | **Hourly cap.** | At most `BROADCAST_MAX_PER_HOUR` (default 10) broadcasts created per hour by all admins together → `400`. |
| 7 | **Every broadcast needs a reason and is audited.** | `notification.ops_audit_log`: `BROADCAST_CREATED` / `BROADCAST_SCHEDULED` / `BROADCAST_CANCELLED`, target `BROADCAST` + id, before/after JSON, reason. (On-demand emailed reports are audited there too as `REPORT_SENT`.) |
| 8 | **"SENT" means accepted by the provider**, not delivered (PRD §18). | Show it as "Accepted". |

## 1. Compose

```json
{
  "title": "Third Mainland Bridge closed",          // 1–80 chars - push title, email subject
  "body": "Closed Sunday 6am–2pm. Leave 30 min early.", // 1–1000 chars
  "channels": ["PUSH", "SMS"],                        // PUSH | SMS | EMAIL | WHATSAPP, at least one
  "audience": { "type": "ACTIVE_RIDERS", "activeDays": 30 },
  "urgent": false,                                    // optional, default false
  "sendAt": "2026-11-14T07:30:00Z",                   // optional - omit to send now; up to 90 days ahead
  "reason": "Road closure notice from LASG"           // required to create, not to preview
}
```

**Audiences** (`audience.type`):

| type | who | extra |
|---|---|---|
| `ALL_RIDERS` | every rider account | |
| `ALL_DRIVERS` | every driver account | |
| `VERIFIED_DRIVERS` | drivers whose verification is `APPROVED` | |
| `ACTIVE_RIDERS` | riders who completed a trip in the last N days | `activeDays` 1–365 |
| `USERS` | a list | `ids` (user ids) and/or `refs` (`R-0DE4B2`, `D-7C34F1`), up to 5,000 |

**Channels:** `PUSH` also puts the message in the app's notification bell. `SMS` text is `CoRyd: <title> - <body>`, cut at
480 characters. `EMAIL` goes to the address on the rider/driver profile (body + "- The CoRyd team"). `WHATSAPP` needs
`WHATSAPP_ENABLED=true` and an approved template id in `WHATSAPP_TEMPLATE_BROADCAST` (placeholders `title`, `message`);
otherwise choosing it is a `400` and the preview shows 0 reachable.

### `POST /broadcasts/preview` → counts, no side effects
```json
{ "recipients": 412, "optedOut": 9, "willReceive": 403,
  "reachable": { "PUSH": 380, "SMS": 405 },
  "unmatched": [], "scheduledFor": "2026-11-14T07:30:00Z", "deferredForQuietHours": false, "smsSegments": 1,
  "sample": { "pushTitle": "…", "pushBody": "…", "sms": "CoRyd: …", "emailSubject": "…", "emailBody": "…", "whatsapp": "…" },
  "warnings": [] }
```
- `reachable` only lists the chosen channels: `PUSH` = people with the app installed (a device token), `SMS`/`WHATSAPP` = with a phone, `EMAIL` = with an email.
- Over 20,000 recipients `optedOut`, `willReceive` and `reachable.PUSH` are `null` (a warning says they're counted at send time).
- `unmatched`: listed ids/refs that are nobody or suspended (`USERS` only).

### `POST /broadcasts` → the broadcast (`View`, section 3), message `"Broadcast scheduled"` or `"Scheduled for after quiet hours"`.

| Refusal (`400`) | when |
|---|---|
| "Give a reason…" | no reason (checked in ops-service first) |
| "The title needs 1 to 80 characters" / "The message needs 1 to 1000 characters" | |
| "Choose at least one channel…" / "Choose an audience…" | |
| "WhatsApp isn't available for broadcasts…" | WHATSAPP without a template |
| "Riders active in the last N days needs N between 1 and 365" | |
| "List at least one user id or reference" / "'X' isn't a CoRyd reference…" | `USERS` |
| "sendAt is in the past…" / "…at most 90 days ahead" | |
| "Nobody is in this audience - not found: …" | |
| "Only 10 broadcasts can be created in an hour…" | |

## 2. Cancel

### `POST /broadcasts/{id}/cancel` `{ "reason": "Bridge reopened" }`
- `SCHEDULED` → never goes. `SENDING` → stops before the next recipient; those already sent stay sent, the rest become
  `SKIPPED` ("Broadcast cancelled"). Anything else → `400`. No reason → `400`.

## 3. History

### `GET /broadcasts?status=SCHEDULED&status=SENDING&page&size` - newest first (`size` ≤ 50).
### `GET /broadcasts/{id}`
```json
{ "id": "…", "title": "…", "body": "…", "channels": ["PUSH","SMS"],
  "audience": { "type": "ACTIVE_RIDERS", "activeDays": 30, "ids": null, "refs": null },
  "urgent": false, "status": "SENT",              // SCHEDULED | SENDING | SENT | CANCELLED | FAILED
  "requestedFor": "…", "scheduledFor": "…", "deferredForQuietHours": false, "reason": "…",
  "createdBy": "…", "createdByName": "Ada Ops", "createdAt": "…",
  "cancelledBy": null, "cancelledByName": null, "cancelReason": null, "cancelledAt": null,
  "startedAt": "…", "finishedAt": "…", "lastError": null,
  "stats": { "recipients": 412, "sent": 395, "partial": 4, "failed": 4, "skipped": 9, "pending": 0,
             "byChannel": { "PUSH": { "SENT": 370, "FAILED": 10, "NO_DEVICE": 23 }, "SMS": { "SENT": 398, "NO_PHONE": 5 } } } }
```
- Before sending starts `stats.recipients` is the audience count at creation; after, the resolved recipient list.
- `FAILED` broadcast: something stopped the sender (e.g. profile-service unreachable) - `lastError` says what.

### `GET /broadcasts/{id}/recipients?status=FAILED&page&size` (size ≤ 100)
`{ userId, role, ref, status, skipReason, push, sms, email, whatsapp, error, sentAt }` - per channel:
`SENT` · `FAILED` · `NO_DEVICE` · `NO_PHONE` · `NO_EMAIL` · `OPTED_OUT` (WhatsApp off) · `UNAVAILABLE` (no WhatsApp template); `null` = channel not chosen.
Recipient `status`: `SENT` (≥1 channel accepted, none failed) · `PARTIAL` (some accepted, some failed) · `FAILED` · `SKIPPED` (`skipReason`: "Turned announcements off", "No way to reach them on the chosen channels", "Broadcast cancelled") · `PENDING`.

## 4. What the mobile app sees

- **Push:** title/body as composed; data `type: "BROADCAST"`, `referenceId: <broadcast id>`. No deep link - open the notifications screen.
- **Bell:** a feed entry with `eventType: "BROADCAST"` in `GET /notification/{userId}` (and the unread count), for everyone in a PUSH broadcast - even without a device token.
- **SMS / email / WhatsApp:** as in section 1. Every send is also a `notification_log` row with `event_type = BROADCAST`.
- The app's existing announcements switch (`promotionalOptIn`) is what rule 3 honours; the WhatsApp switch (`whatsappOptIn`) only stops WhatsApp.

## 5. Storage

`notification.broadcasts`, `notification.broadcast_recipients` (unique per broadcast + user), `notification.ops_audit_log` (coryd:135–136).

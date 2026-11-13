# Ops Console: Audit log

Phase 6 (part A). One feed of every ops action — who approved which driver, who refunded what, who suspended whom, who
changed an admin's role — merged from the audit tables each service already keeps.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops`.
- **Auth:** `OPS_ADMIN` token with ops role **`SUPER_ADMIN`** (permission `AUDIT_VIEW`). Other ops roles get
  `403 Your ops role (X) can't view the audit log`.
- **Envelope:** `{ responseId, timestamp, success, message, data }`.
- **Times:** instants in UTC (ISO-8601). Date filters are **Lagos calendar days**.

---

## 1. What's in it

| Category | Kept by (table) | Actions | Target type(s) |
|---|---|---|---|
| `ACCOUNT` | identity (`account_actions`) | `SUSPENDED`, `REINSTATED`, `SIGNED_OUT_EVERYWHERE`, `DETAILS_EDITED`, `VEHICLE_EDITED`, `LICENCE_ATTEMPTS_RESET`, `IDENTITY_ATTEMPTS_RESET` | `ACCOUNT` (the person's user id) |
| `TEAM` | identity (`account_actions`) | `OPS_ADMIN_INVITED`, `OPS_INVITE_RESENT`, `OPS_INVITE_REVOKED`, `OPS_INVITE_ACCEPTED`, `OPS_ROLE_CHANGED`, `OPS_ADMIN_DEACTIVATED`, `OPS_ADMIN_REACTIVATED` | `ADMIN` (the admin's user id) |
| `VERIFICATION` | profile (`verification_decision_log`) | `APPROVED`, `OVERRIDE_APPROVED`, `REJECTED`, `RESUBMISSION_REQUESTED`, `SENT_BACK_TO_REVIEW` (older entries without an action show their status) | `VERIFICATION` (the person's user id; `subject` names the document, e.g. "Insurance certificate", "Identity (NIN)") |
| `TRIPS` | trip (`ops_audit_log`) | `CANCEL_TRIP`, `CANCEL_BOOKING`, `RESOLVE_STUCK_COMPLETED`, `RESOLVE_STUCK_CANCELLED` | `ROUTE`, `BOOKING` |
| `SAFETY` | trip (`ops_audit_log`) | `SOS_ACKNOWLEDGED`, `SOS_NOTE`, `SOS_ESCALATED`, `SOS_RESOLVED`, `REPORT_ASSIGNED`, `REPORT_NOTE`, `REPORT_RESOLVED`, `REPORT_DISMISSED`, `RATING_HIDDEN`, `RATING_RESTORED` | `SOS`, `ISSUE`, `RATING` |
| `MONEY` | payment (`ops_money_audit`) | `REFUND_*`, `PAYOUT_RETRY*`, `PAYOUT_RETRIED`, `DEBT_*`, `REQUEST_APPROVED/REJECTED/CANCELLED/RESUBMITTED`, `PAYOUT_ACCOUNT_REVEALED` | `BOOKING`, `FARE_HOLD`, `SETTLEMENT`, `DRIVER`, `MONEY_REQUEST`, `PAYOUT_ACCOUNT` |

Left out on purpose: Smile ID's own late results (`PROVIDER_RESULT` — not an ops action) and the verification log's
`ATTEMPTS_RESET` (the same reset is already in the account history as `LICENCE_/IDENTITY_ATTEMPTS_RESET`).
Not audited anywhere yet (so not here): beta-signup "invited" marks, document-expiry reminders, bank-detail views other than
the reveal. Phase 6 part B (settings, broadcasts) adds its own source when it lands.

---

## 2. `GET /audit`

| Param | Meaning |
|---|---|
| `actorId` | The admin who did it (user id — use `/team` for the picker). |
| `action` | One or more action names (`action=SUSPENDED&action=REINSTATED` or comma-separated). |
| `category` | One or more of `ACCOUNT`, `TEAM`, `VERIFICATION`, `TRIPS`, `SAFETY`, `MONEY`. Unknown → `400`. |
| `targetType` | One or more target types from the table above. Unknown → `400` listing the allowed ones. |
| `targetId` | Exact target id. For money, an approval request id also matches every action on that request. |
| `from`, `to` | `YYYY-MM-DD`, **Lagos** days, both inclusive (`to=2026-11-12` runs to 2026-11-13 00:00 Lagos). `from` after `to` → `400`. |
| `search` | Case-insensitive, matches reason/note, actor name and target id. A console reference such as `T-2F2F53` searches by the id it was made from. |
| `cursor` | `nextCursor` from the previous page. Leave out for the first page. A malformed one → `400 Invalid cursor - start again from the first page`. |
| `size` | 1–100, default 50. |

Always newest first. Response:

```json
{
  "items": [
    {
      "id": "TRIP:5d1c…",
      "at": "2026-11-12T10:00:08Z",
      "source": "TRIP",
      "category": "TRIPS",
      "action": "CANCEL_BOOKING",
      "actor": { "id": "0b5e…", "name": "Ada Okafor" },
      "target": { "type": "BOOKING", "id": "524ea3aa-…", "ref": "B-524EA3", "name": null },
      "subject": "Fault: RIDER",
      "reason": "Rider asked to cancel",
      "before": { "status": "CONFIRMED" },
      "after": { "status": "CANCELLED_OPS" },
      "amount": null,
      "relatedId": "2f2f53aa-…",
      "relatedRef": "T-2F2F53"
    }
  ],
  "hasMore": true,
  "nextCursor": "eyJwIjp7…",
  "size": 50
}
```

- `id` is `{source}:{row id}` — unique across the feed.
- `target.ref`: the console's short reference where one exists (`T-` route, `B-` booking, `S-` SOS, `D-` driver / payout account).
  `target.name`: the person's name for `ACCOUNT`, `ADMIN`, `VERIFICATION`, `DRIVER` and `PAYOUT_ACCOUNT` targets.
- `actor.name` is the name recorded at the time; filled from the account if it wasn't recorded. `actor` is `null` for
  entries recorded before actors were kept.
- `before` / `after`: JSON as recorded (`null` when nothing was). Account and team actions: `{field: value}` per changed
  field. Verification: `{status}` before, `{status, overrodeProvider?, providerOutcome?}` after.
- `amount`: money actions only (naira). `relatedId`: the trip a booking/SOS action was on (`relatedRef` `T-…`), or the
  approval request of a money action.
- No total count (see §4).
- If a service that keeps part of the log is down, the call fails with that service's status (`502`/`503`, its message)
  rather than showing a feed with rows silently missing.

## 3. `GET /audit/export` — CSV

Same filters (no `cursor`/`size`), plus `limit` (1–10,000, default 10,000). `text/csv; charset=UTF-8` with a BOM (opens in
Excel), `Content-Disposition: attachment; filename="coryd-audit-YYYY-MM-DD.csv"`.

Headers: `X-Audit-Rows` (rows written), `X-Audit-Truncated` (`true` when more rows matched than were exported — narrow the
filter). Both are exposed to the browser.

Columns: `Time (Lagos)` (`yyyy-MM-dd HH:mm:ss`), `Category`, `Action`, `Actor`, `Actor ID`, `Target type`, `Target ID`,
`Target ref`, `Target name`, `Subject`, `Reason`, `Amount`, `Before`, `After`, `Related ID`, `Entry ID`. Cells starting with
`= + - @` are prefixed with `'` so a spreadsheet doesn't run them as formulas.

---

## 4. How the merge works, and its limits

Each owning service exposes `GET /internal/ops/audit` (internal key + the admin's forwarded token) with one filter shape
(`common`'s `OpsAuditQuery`: `actorId, actions, categories, targetTypes, targetId, from, to, search, beforeAt, beforeId,
size ≤ 500`) and returns `{items, hasMore}` newest first, ordered `(created_at desc, id desc)` with a keyset cursor
(`beforeAt` + `beforeId`). ops-service:

1. skips sources that can't match the filter (e.g. `category=MONEY` only asks payment-service);
2. asks each remaining source for up to `size` entries after **its own** position in the cursor;
3. merges everything newest first (same instant: by source name, then the source's own order) and keeps the first `size`;
4. the next cursor stores, per source, the last entry taken from it (unchanged if none was) and which sources are used up.

This is exact for any page size: an entry in the true top `size` is always in its own source's top `size`, and what a page
takes from each source is a prefix of that source's answer — no gaps, no repeats.

Limits:
- **Cost:** up to (sources × size) rows read per page (4 × 50 by default); each source query is indexed on
  `(created_at desc, id desc)` (coryd:123–126). The export reads 500 per source per round, up to 10,000 rows.
- **No total count** — counting four tables on every filter change isn't worth it; the console shows "Load more" while `hasMore`.
- **No jump to page N** — only forward from a cursor. A cursor stays valid indefinitely (it's positions, not offsets).
- **Search** is a substring scan on the matched rows (no full-text index); fine at today's volumes.
- A cursor made under one filter must be used with the same filter.
- Sources' clocks: entries are ordered by each service's own `created_at`; a few ms of skew between services can
  interleave near-simultaneous actions slightly out of real order.

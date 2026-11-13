# Ops Console: Admin team and ops roles

Phase 6 (part A). Who can use the console, and what each person may do in it: the admin team (invite, resend/revoke an
invite, change a role, deactivate/reactivate), the four ops roles, `GET /me`, and how the roles are enforced.

**Basics:**
- **Base URL:** `https://api-dev.coryd.ng/ops` (team and `/me`); invite acceptance is on identity: `https://api-dev.coryd.ng/identity`.
- **Auth:** an **`OPS_ADMIN`** token, as for every console call. Riders and drivers get `403`, no token `401`.
- **Ops role:** the token carries an extra claim, `opsRole` (`SUPER_ADMIN`, `VERIFICATION`, `FINANCE`, `SUPPORT`). An
  `OPS_ADMIN` token **without** the claim (signed in before this shipped) counts as `SUPER_ADMIN`, which is what every admin
  was before. An unknown value is refused everywhere (`403 Your ops role (X) isn't recognised - sign out and sign in again`).
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors have `success: false` and a `message` you can show as-is.
- **Who did it:** always taken from the token. The console never sends an actor.

---

## 1. Roles and permissions

| Permission | What it covers | SUPER_ADMIN | VERIFICATION | FINANCE | SUPPORT |
|---|---|:-:|:-:|:-:|:-:|
| `CONSOLE_BASICS` | `GET /me`, `GET /team/roles`, live stream (`/stream/**`), dashboard metrics (`GET /metrics/**`) | ✓ | ✓ | ✓ | ✓ |
| `DIRECTORY_VIEW` | Riders/Drivers lists and profiles, account history, deletion requests (read only) | ✓ | ✓ | ✓ | ✓ |
| `VERIFICATION_VIEW` | Identity checks, verification queue, driver documents, document expiry (read) | ✓ | ✓ | | |
| `VERIFICATION_DECIDE` | Approve / reject / request resubmission / override, give tries back, partial-name review, expiry reminders | ✓ | ✓ | | |
| `MONEY_VIEW` | Transactions, refunds, approvals, payouts, earnings & debt, finance dashboard, pricing, money audit (read) | ✓ | | ✓ | ✓ |
| `MONEY_MANAGE` | Manual refunds, approve/reject/cancel/retry requests, payout & refund retries, debt adjustments, bank-detail reveal | ✓ | | ✓ | |
| `TRIPS_VIEW` | Trips, live, stuck, last-minute time changes, cancellations log | ✓ | | ✓ | ✓ |
| `TRIPS_MANAGE` | Cancel a trip or booking, resolve a stuck trip | ✓ | | | ✓ |
| `SAFETY_VIEW` | SOS queue, reports & disputes, ratings (read) | ✓ | | | ✓ |
| `SAFETY_MANAGE` | Acknowledge / note / escalate / resolve SOS, work reports, hide/restore ratings | ✓ | | | ✓ |
| `ACCOUNT_MANAGE` | Suspend, reinstate, sign out everywhere, edit details, edit vehicle | ✓ | | | ✓ |
| `ACCOUNT_DELETE` | Delete an account | ✓ | | | |
| `GROWTH_MANAGE` | Beta waitlist and marking people invited | ✓ | | | ✓ |
| `TEAM_MANAGE` | Everything under `/team` except `GET /team/roles` | ✓ | | | |
| `AUDIT_VIEW` | `GET /audit`, `GET /audit/export` ([audit-log.md](audit-log.md)) | ✓ | | | |

### Endpoint → permission (ops-service `OpsAccessPolicy`, first match wins)

| Method | Path (under `/ops`) | Permission |
|---|---|---|
| GET | `/me`, `/metrics/**`, `/team/roles` | `CONSOLE_BASICS` |
| any | `/me/mfa/**` (own two-factor sign-in, [security.md](security.md)) | `CONSOLE_BASICS` |
| any | `/stream/**` | `CONSOLE_BASICS` |
| any | `/team/**` | `TEAM_MANAGE` |
| any | `/audit/**` | `AUDIT_VIEW` |
| GET / other | `/identity-verification/**` | `VERIFICATION_VIEW` / `VERIFICATION_DECIDE` |
| GET | `/drivers/verification/**`, `/drivers/*/verification/**` | `VERIFICATION_VIEW` |
| other | `/drivers/*/verification/**` | `VERIFICATION_DECIDE` |
| GET / other | `/document-expiry/**` | `VERIFICATION_VIEW` / `VERIFICATION_DECIDE` |
| GET / other | `/money/**` | `MONEY_VIEW` / `MONEY_MANAGE` |
| GET | `/drivers/**`, `/riders/**`, `/users/*/account-history`, `/account-deletions/**` | `DIRECTORY_VIEW` |
| PATCH | `/drivers/*/vehicles/*` | `ACCOUNT_MANAGE` |
| DELETE | `/users/*` | `ACCOUNT_DELETE` |
| any | `/users/**` | `ACCOUNT_MANAGE` |
| GET | `/trips/**`, `/cancellations/**` | `TRIPS_VIEW` |
| other | `/trips/**`, `/bookings/**` | `TRIPS_MANAGE` |
| GET / other | `/sos/**`, `/reports/**`, `/ratings/**` | `SAFETY_VIEW` / `SAFETY_MANAGE` |
| any | `/beta-signups/**` | `GROWTH_MANAGE` |
| — | **anything else** | **SUPER_ADMIN only** |

A new endpoint is closed to the non-super roles until a rule is added (`ops-service/.../access/OpsAccessPolicy.java`).
Phase 6 part B's endpoints (settings, broadcasts, reports, system health) fall in "anything else" until it adds rules.

### The refusal

Checked in ops-service before anything downstream is called:

```http
HTTP/1.1 403
{ "success": false, "message": "Your ops role (FINANCE) can't cancel or resolve trips and bookings" }
```

Other wordings: `… can't manage the admin team`, `… can't view the audit log`, `… can't move money (refunds, approvals,
payouts, debt) or reveal bank details`, `… can't use this part of the console - it's for super admins` (no rule).

---

## 2. `GET /me` — who is signed in

Any ops role. Call it once on load: hide what `permissions` doesn't include. Also moves the admin's **last seen** on.

```json
{
  "userId": "0b5e…",
  "firstName": "Fola", "lastName": "Ade", "name": "Fola Ade",
  "email": "fola@coryd.ng",
  "opsRole": "FINANCE",
  "opsRoleLabel": "Finance",
  "permissions": ["CONSOLE_BASICS", "DIRECTORY_VIEW", "MONEY_MANAGE", "MONEY_VIEW", "TRIPS_VIEW"],
  "mfaEnabled": true, "mfaRequired": false,
  "sessionIdleMinutes": 30, "sessionAbsoluteHours": 12, "sessionExpiresAt": "2026-11-13T22:00:00Z"
}
```

The last five fields are phase 7 (two-factor sign-in and session limits, [security.md](security.md) §2).

`opsRole`/`permissions` are what the **token** grants — exactly what the endpoints enforce. `email` is `null` for an
admin who signs in by phone, or when identity-service can't be reached (the call still answers).

`GET /team/roles` (any ops role) returns every role with its label and permissions, for the role picker:
`[{ "role": "SUPER_ADMIN", "label": "Super admin", "permissions": [...] }, …]`.

---

## 3. The admin team (SUPER_ADMIN only)

### Admin object

```json
{
  "userId": "7c1e…",
  "firstName": "Ngozi", "lastName": "Eze", "fullName": "Ngozi Eze",
  "email": "ngozi@coryd.ng",
  "phone": null,
  "opsRole": "FINANCE", "opsRoleLabel": "Finance",
  "permissions": ["CONSOLE_BASICS", "DIRECTORY_VIEW", "MONEY_MANAGE", "MONEY_VIEW", "TRIPS_VIEW"],
  "status": "INVITED",
  "lastSeenAt": null,
  "invitedAt": "2026-11-13T10:00:00Z", "invitedById": "0b5e…", "invitedByName": "Ada Okafor",
  "inviteExpiresAt": "2026-11-16T10:00:00Z",
  "deactivatedAt": null, "deactivationReason": null,
  "createdAt": "2026-11-13T10:00:00Z",
  "mfaEnabled": false,
  "you": false
}
```

| `status` | Meaning | Actions that make sense |
|---|---|---|
| `ACTIVE` | Has a password and console access | change role, deactivate |
| `INVITED` | Invite link valid and unused (`inviteExpiresAt`) | resend, revoke, change role |
| `INVITE_EXPIRED` | The 72h link ran out unused | resend, change role |
| `INVITE_REVOKED` | The invite was withdrawn | resend (invites them again) |
| `DEACTIVATED` | Console access removed (`deactivatedAt`, `deactivationReason`) | reactivate |
| `SUSPENDED` | The whole account is suspended (an account action, see accounts.md) | — |

`lastSeenAt`: their last console sign-in, token refresh or console load (`GET /me`, at most once a minute).
`invitedBy*` is `null` for admins who existed before invites. `you`: this row is the signed-in admin.

### Calls

| Call | Body | Notes |
|---|---|---|
| `GET /team` | — | Every account holding `OPS_ADMIN`, ordered by status then name. Small list, no paging. |
| `GET /team/{adminId}` | — | One admin. `404 Admin not found` for an id that isn't an ops admin. |
| `POST /team/invites` | `{ email, firstName, lastName, opsRole }` | Creates the account (email sign-in, no phone) and emails a one-time set-password link valid **72 hours**. |
| `POST /team/{adminId}/invite/resend` | `{ reason? }` (body optional) | Only for someone who hasn't set a password yet. Ends the earlier link, sends a new one (new 72h). |
| `POST /team/{adminId}/invite/revoke` | `{ reason? }` | Only while an invite is pending. The link stops working. |
| `POST /team/{adminId}/role` | `{ opsRole, reason? }` | Their access tokens issued so far stop working at once (they carry the old role); the console's next token refresh gets the new role. |
| `POST /team/{adminId}/deactivate` | `{ reason }` (required) | Console access removed **now**: every session on every device ends within seconds (same `tokens_valid_after` mechanism as sign-out-everywhere), console sign-in refused, pending invites revoked. Their account is **not** suspended. |
| `POST /team/{adminId}/reactivate` | `{ reason }` (required) | Console access back. The ended sessions stay ended — they sign in again. |
| `POST /team/{adminId}/mfa/reset` | `{ reason }` (required) | Phase 7: clears their two-factor sign-in and ends every session; they set it up again at next sign-in ([security.md](security.md) §4). |

Every call returns the updated admin object (`data`).

### Refusals (`400` unless noted)

| Situation | Message |
|---|---|
| Not a super admin (now — checked against the database, not just the token) | `403 Only a super admin can manage the admin team` (identity) or `403 Your ops role (X) can't manage the admin team` (ops) |
| Deactivate yourself | `You can't deactivate yourself - ask another super admin` |
| Change your own role | `You can't change your own ops role - ask another super admin` |
| Demote or deactivate the last active super admin | `{name} is the last super admin - make someone else a super admin first` |
| Same role again | `{name} is already Finance` |
| Deactivate/reactivate without a reason | `Give a reason for deactivating this admin` / `… reactivating …` |
| Already deactivated / not deactivated | `{name} is already deactivated` / `{name} isn't deactivated` |
| Email already an admin / in use | `{email} is already on the admin team` / `That email address is already in use` |
| Resend for someone with a password | `{name} has already set up their account - there's no invite to resend` |
| Resend while deactivated | `Reactivate {name} before sending them a new invite` |
| Revoke with nothing pending | `There's no pending invite to revoke for {name}` |
| Bad email / missing names / unknown `opsRole` | validation message, e.g. `Enter a valid email address` |

The last-super-admin rule is checked with the admin rows locked, so two super admins demoting each other at the same
moment can't leave nobody in charge.

---

## 4. Accepting an invite (identity-service, no session)

The email (sent by notification-service, event type `OPS_ADMIN_INVITE`) links to the console:
`{CONSOLE_URL}/accept-invite?token=…` (`coryd.ops.console-url`, default `https://admin.coryd.ng`). **The console needs
this page.** It calls:

### `GET /identity/auth/ops-invite?token=…`

```json
{ "email": "ngozi@coryd.ng", "firstName": "Ngozi", "lastName": "Eze", "opsRole": "FINANCE", "opsRoleLabel": "Finance",
  "invitedByName": "Ada Okafor", "expiresAt": "2026-11-16T10:00:00Z" }
```

### `POST /identity/auth/ops-invite/accept`

```json
{ "token": "…", "password": "8 to 72 characters", "confirmPassword": "…" }
```

Returns the same session as `/auth/login` (`activeRole: "OPS_ADMIN"`, plus `opsRole` and `email`) — the admin is signed
in. Store it as a normal console session. **Phase 7:** when two-factor sign-in is required (`OPS_MFA_REQUIRED`), it returns
the `mfaEnrollmentRequired` step instead ([security.md](security.md) §1.3-1.4).

| Link state | `400` message |
|---|---|
| Used | `This invite link has already been used - sign in with your email and password` |
| Expired (72h) | `This invite link has expired - ask a super admin to send a new one` |
| Revoked, replaced by a resend, admin deactivated, or unknown | `This invite link is no longer valid - ask a super admin to send a new one` (unknown: `This invite link isn't valid - …`) |
| Passwords differ | `Password and confirmation do not match` |

Only the SHA-256 of the token is stored (`identity.ops_invites`); the raw token exists only in the email.

---

## 5. Signing in

`POST /identity/auth/login` with `{ "phone": "<email>", "password": "…", "role": "OPS_ADMIN" }` — the console already
sends the email in `phone`. An identifier with `@` is looked up by email (case-insensitive). The response now also has
`opsRole` and `email` (null for rider/driver sessions). Messages: `Incorrect email or password`; a deactivated admin gets
`401 Your ops console access has been removed. Ask a super admin to restore it.` (also on token refresh).

**Phase 7:** an admin with two-factor sign-in gets `{ mfaRequired, mfaToken, mfaTokenExpiresAt }` instead of a session,
and one who must enroll gets `{ mfaEnrollmentRequired, enrollToken, enrollTokenExpiresAt }`. Console access tokens last
15 minutes, refresh tokens 30 idle minutes / 12 hours in all. See [security.md](security.md).

---

## 6. Data and migrations

- `identity.users` (coryd:120): `phone` may now be null (email-invited admins); new `email` (unique, lower-case),
  `ops_role`, `invited_by`, `invited_by_name`, `invited_at`, `ops_deactivated_at`, `ops_deactivated_by`,
  `ops_deactivation_reason`, `last_seen_at`.
- coryd:121: every account already holding `OPS_ADMIN` becomes `SUPER_ADMIN`; an admin created by hand with their email in
  the `phone` column (the console's test admin, `admin@coryd.africa`) gets it copied to `email`.
- coryd:122: `identity.ops_invites` (token hash, expiry, used/revoked, who sent it).
- Team actions are written to the admin's account history (`identity.account_actions`) as `OPS_ADMIN_INVITED`,
  `OPS_INVITE_RESENT`, `OPS_INVITE_REVOKED`, `OPS_INVITE_ACCEPTED`, `OPS_ROLE_CHANGED` (before/after role),
  `OPS_ADMIN_DEACTIVATED`, `OPS_ADMIN_REACTIVATED` — the audit log shows them as category `TEAM`. Phase 7 adds
  `MFA_ENABLED`, `MFA_DISABLED`, `MFA_RESET`, `RECOVERY_CODE_USED`, `MFA_RECOVERY_CODES_REGENERATED` (also `TEAM`).

## 7. Known limits

- Roles are enforced in **ops-service** and, since phase 7, in the owning services too: profile- and payment-service's
  ops-facing paths need the internal key (ops-service) or an ops role with the permission ([security.md](security.md) §7).
- The live stream (`GET /stream`) carries SOS alerts to every ops role. Since phase 7 it closes (`bye`, `SESSION_ENDED`)
  within seconds of a deactivation or role change.
- Invited admins sign in by email only; giving console access to an existing rider/driver phone account isn't supported
  through invites.

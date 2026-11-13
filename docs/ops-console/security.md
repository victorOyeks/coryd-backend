# Ops Console: Security (two-factor sign-in, session limits, live-stream cut-off, role checks)

Phase 7. How an ops admin signs in with a second factor, how long a console session lasts, what happens to the live
stream when a session is ended, and where ops roles are enforced. Riders and drivers are not affected by any of it.

**Basics:**
- Sign-in is on identity: `https://api-dev.coryd.ng/identity`. The signed-in admin's own settings and the team are on
  ops: `https://api-dev.coryd.ng/ops` (normal `OPS_ADMIN` bearer token).
- **Envelope:** `{ responseId, timestamp, success, message, data }`. Errors have `success: false` and a `message` you can
  show as-is. Validation errors (a missing field) are `400` with the field name first, e.g. `code: Enter the 6-digit code from your app`.
- Codes are **TOTP (RFC 6238)**: SHA-1, 30-second steps, 6 digits. Any authenticator app works (Google Authenticator,
  Microsoft Authenticator, 1Password, Authy). A code is accepted for the current step and one step either side (about
  ±30 s of clock drift), and **only once** - the same code can't be used twice, even in a new sign-in.

---

## 1. Signing in

### 1.1 `POST /identity/auth/login` (unchanged request)

```json
{ "phone": "fola@coryd.ng", "password": "…", "role": "OPS_ADMIN" }
```

The answer's `data` is **one of three shapes**. Check the flags in this order:

| `data` has | Meaning | Next |
|---|---|---|
| `mfaRequired: true` | Two-factor sign-in is on for this admin | Ask for a code, call `/auth/mfa/verify` |
| `mfaEnrollmentRequired: true` | Two-factor is required (`OPS_MFA_REQUIRED`) and they haven't set it up | Enrollment screens, §1.3 |
| `accessToken` | A normal session (two-factor off and not required) | Signed in, as before |

```json
{ "mfaRequired": true, "mfaToken": "q3J…x9", "mfaTokenExpiresAt": "2026-11-13T10:05:00Z" }
```

```json
{ "mfaEnrollmentRequired": true, "enrollToken": "Zk1…a0", "enrollTokenExpiresAt": "2026-11-13T10:15:00Z" }
```

No access or refresh token comes back in either of these. Wrong password, deactivated or suspended admin: unchanged
(`400 Incorrect email or password`, `401 Your ops console access has been removed…`, `401 This account has been suspended…`).

### 1.2 `POST /identity/auth/mfa/verify` (no session)

```json
{ "mfaToken": "q3J…x9", "code": "492039" }
```

`code` is the 6 digits from the app **or** an unused recovery code (`xxxx-xxxx`; case, spaces and the dash don't matter).
Success: `data` is the normal session - exactly what `/auth/login` returned before (`accessToken`, `refreshToken`,
`expiresIn`, `refreshExpiresIn`, `activeRole: "OPS_ADMIN"`, `opsRole`, `email`, …) plus `sessionExpiresAt` (§2).

| Situation | Status | `message` |
|---|---|---|
| Wrong code (attempts 1–4) | 401 | `That code didn't work - try the latest one from your app` |
| 5th wrong code, and anything after it on that token | 401 | `Too many attempts - sign in again` |
| Token used already, expired (5 minutes), unknown, or two-factor was reset in between | 401 | `Your sign-in has expired - sign in again` |
| Admin deactivated / suspended since the password step | 401 | the usual deactivated / suspended message |

`mfaToken` is **single use** (a successful verify spends it) and lasts **5 minutes**. Signing in with a recovery code
spends that code and is recorded (`RECOVERY_CODE_USED`); the console should then suggest making new codes (§3.4).

### 1.3 Enrollment at sign-in (only when `OPS_MFA_REQUIRED=true`)

`enrollToken` lasts **15 minutes** (time to install an app) and allows 5 wrong codes, like `mfaToken`.

**`POST /identity/auth/mfa/enroll/setup`** `{ "enrollToken": "…" }` →

```json
{
  "secret": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
  "otpauthUrl": "otpauth://totp/CoRyd%20Ops:fola%40coryd.ng?secret=JBSW…&issuer=CoRyd%20Ops&algorithm=SHA1&digits=6&period=30",
  "issuer": "CoRyd Ops",
  "account": "fola@coryd.ng"
}
```

Show `otpauthUrl` as a QR code and `secret` for typing in. Calling setup again within 15 minutes returns the **same**
secret (a reload doesn't break a QR they already scanned).

**`POST /identity/auth/mfa/enroll/confirm`** `{ "enrollToken": "…", "code": "492039" }` →

```json
{
  "session": { "accessToken": "…", "refreshToken": "…", "activeRole": "OPS_ADMIN", "opsRole": "SUPPORT", "email": "…", "sessionExpiresAt": "…", "…": "…" },
  "recoveryCodes": ["k7rd-2mxa", "…10 codes…"]
}
```

The recovery codes are shown **once** - make the admin save them before continuing. Errors: wrong code / too many /
expired as in §1.2 (401); `400 Set up your authenticator app first` if confirm comes before setup.

### 1.4 Accepting an invite

`POST /identity/auth/ops-invite/accept` answers like sign-in: a session, or - when `OPS_MFA_REQUIRED=true` - the
`mfaEnrollmentRequired` shape of §1.1 (the new admin sets up two-factor before their first session).

### 1.5 Other ways in

A rider/driver session can't become a console session (`POST /auth/roles/switch` to `OPS_ADMIN`, or an OTP sign-in of an
ops-only account) while two-factor applies to that account: `400 Sign in to the ops console with your email, password and two-factor code`.

---

## 2. Session lifetime (ops console only)

| | Ops console | Riders and drivers (unchanged) |
|---|---|---|
| Access token | **15 minutes** (`expiresIn: 900`) | 24 hours |
| Refresh token, idle | **30 minutes**; every refresh slides it | 30 days |
| Whole session, absolute | **12 hours** from sign-in, however active | none |

- `refreshExpiresIn` is the idle window, never past the absolute end. `sessionExpiresAt` (in the sign-in/refresh answer,
  and on `GET /ops/me`) is the absolute end.
- After either limit `POST /identity/auth/refresh-token` returns **`401 Your session timed out - sign in again`**. Treat
  it as signed out.
- The console should refresh the access token before its 15 minutes are up (or on a `401` with any other message), and
  warn near `sessionExpiresAt` ("You'll be signed out at 22:00").

### `GET /ops/me` - new fields

```json
{
  "userId": "…", "name": "Fola Ade", "email": "fola@coryd.ng", "opsRole": "FINANCE", "permissions": ["…"],
  "mfaEnabled": true,
  "mfaRequired": false,
  "sessionIdleMinutes": 30,
  "sessionAbsoluteHours": 12,
  "sessionExpiresAt": "2026-11-13T22:00:00Z"
}
```

`mfaEnabled`/`mfaRequired` are `null` if identity-service can't be reached (the call still answers). `sessionExpiresAt`
comes from the token; it is `null` for a token issued before this shipped.

---

## 3. Two-factor for the signed-in admin (`/ops/me/mfa`, every ops role)

| Call | Body | `data` |
|---|---|---|
| `GET /me/mfa` | — | `{ mfaEnabled, mfaRequired, enabledAt, recoveryCodesLeft, setupPending, accessTokenMinutes, sessionIdleMinutes, sessionAbsoluteHours }` |
| `POST /me/mfa/setup` | — | `{ secret, otpauthUrl, issuer: "CoRyd Ops", account }` - pending until confirmed; nothing changes yet |
| `POST /me/mfa/enable` | `{ code }` | `{ recoveryCodes: [10] }` - shown once |
| `POST /me/mfa/disable` | `{ code, reason }` | the `GET /me/mfa` shape (`mfaEnabled: false`) |
| `POST /me/mfa/recovery-codes` | `{ code }` | `{ recoveryCodes: [10] }` - the old ones stop working |

`code` is from the app; `disable` and `recovery-codes` also take an unused recovery code (a lost phone). **Wrong codes
here are `400`** (not `401`, so the console doesn't mistake them for a dead session):

| Situation | `400` message |
|---|---|
| Wrong code | `That code didn't work - try the latest one from your app` |
| Setup while on | `Two-factor sign-in is already on - turn it off first to set up a new app` |
| Enable before setup / setup older than 15 min | `Start setting up two-factor sign-in first` |
| Enable while on | `Two-factor sign-in is already on` |
| Disable / new codes while off | `Two-factor sign-in isn't on` |
| Disable without a reason | `Give a reason for turning off two-factor sign-in` |
| Disable while `OPS_MFA_REQUIRED` | `Two-factor sign-in is required for the ops console - it can't be turned off` |

Turning two-factor on does not end the current session.

## 4. Super admin: reset someone's two-factor

`POST /ops/team/{adminId}/mfa/reset` `{ "reason": "Lost their phone" }` (`TEAM_MANAGE`, i.e. super admins) → the admin
object (now `mfaEnabled: false`).

- Clears their secret and recovery codes, closes any sign-in step in progress, and **ends every session they have**
  (same `tokens_valid_after` mechanism as deactivation - their console, including its live stream, is signed out within
  seconds).
- Next sign-in: a plain session, or enrollment when `OPS_MFA_REQUIRED=true`.
- Refusals (`400`): `You can't reset your own two-factor sign-in - ask another super admin`; `Give a reason for resetting two-factor sign-in`;
  `{name} hasn't set up two-factor sign-in`. Non-super-admin: `403 Your ops role (X) can't manage the admin team`.

`GET /team` and `GET /team/{adminId}` rows gain **`mfaEnabled`** (boolean).

## 5. Audit

All recorded in the admin's account history (`identity.account_actions`) and shown in the audit log as category **`TEAM`**,
target type `ADMIN`:

| Action | Actor | Notes |
|---|---|---|
| `MFA_ENABLED` | the admin | from settings or enrollment at sign-in |
| `MFA_DISABLED` | the admin | `reason` |
| `MFA_RESET` | the super admin | `reason`; sessions ended |
| `RECOVERY_CODE_USED` | the admin | changes: `recoveryCodesLeft` before/after |
| `MFA_RECOVERY_CODES_REGENERATED` | the admin | |

## 6. Live stream cut-off

An open `GET /ops/stream` is closed within seconds when that admin's sessions are ended (deactivation, suspension, sign
out everywhere, role change, two-factor reset) - it no longer runs on to its 30 minutes:

```
event: bye
data: {"reason":"SESSION_ENDED","message":"Your session has ended. Please sign in again."}
```

(`message` says "This account has been suspended…" for a suspension.) The existing `bye` with `reason: "EXPIRED"` is
unchanged. On `SESSION_ENDED` the console should **not** reopen the stream - its token refresh will fail too; sign out.
`POST /stream/token` with an ended session's token is `401`, and a stream token minted before the cut-off no longer opens
a stream (`401`, reason in `X-Stream-Error`).

How: ops-service's stream follows the same in-memory revocation registry as every `JwtFilter` (fed by
`coryd-user-tokens-revoked` + identity's snapshot, see [accounts.md §8](accounts.md)); stream tokens now carry the issue
time of the access token they were minted with, so each open stream knows which session it belongs to.

## 7. Ops roles enforced in the owning services too

Before: ops-service enforced the ops role, but some endpoints it calls are on public paths of other services, reachable
through the gateway with **any** `OPS_ADMIN` token (a FINANCE admin could approve a driver straight on profile-service).

Now each owning service checks too (common `OpsPermissionFilter` + `OpsPermissions`, rules in `OpsOwnedPaths`). For an
`OPS_ADMIN` token on these paths the request needs **either** the internal API key (ops-service - which already checked
the role and keeps working unchanged; its clients already send the key on every call) **or** an ops role with the
permission. Otherwise `403 Your ops role (X) can't …` (same wording as ops-service). Riders and drivers are untouched (their
own "only your own" checks still apply); a service's system token (no ops role) counts as super admin, as everywhere.

| Service | Method | Path | Permission |
|---|---|---|---|
| profile | GET | `/drivers/verification/**` (queue, counts) | `VERIFICATION_VIEW` |
| profile | GET | `/drivers/*/verification-status`, `/drivers/*/verification/**` (review, licence, history) | `VERIFICATION_VIEW` |
| profile | other | `/drivers/*/verification` (PATCH), `/drivers/*/verification/**` (approve, reject, save, items, licence reset) | `VERIFICATION_DECIDE` |
| profile | GET / other | `/identity-verification/**` | `VERIFICATION_VIEW` / `VERIFICATION_DECIDE` |
| payment | GET | `/earnings/drivers/**`, `/earnings/transactions/batch`, `/payments/riders/*/history` | `MONEY_VIEW` |
| payment | other | `/earnings/drivers/**` (settlement debt) | `MONEY_MANAGE` |
| payment | any | `/payout-recipients/drivers/**` (bank details) | `MONEY_MANAGE` |
| trip | — | none: every ops endpoint is under `/internal/ops/**` and already needs the internal key | — |

### Two reads that were open to any signed-in user

| Endpoint | Who may read it now | Everyone else |
|---|---|---|
| profile `GET /drivers/{driverId}/documents` | the driver themself; another service (internal key); an ops admin with `VERIFICATION_VIEW` | `403 You can only view your own documents` |
| payment `GET /payments/bookings/{bookingId}` (receipt / Pay Now status) | the rider who booked; the driver of that booking's trip (checked with trip-service); another service (internal key); an ops admin with `MONEY_VIEW` | `403 You can only view payments for your own bookings` |

An ops admin without the permission gets the usual `403 Your ops role (X) can't …`. These answer **403**, not the 401 that
older "only your own" checks in these services use (`You can only view your own payments/earnings`): the apps treat a
401 as signed out. A booking with no payment still answers `404 No payment found for this booking`. If trip-service can't
be reached, a driver (not the rider) is refused rather than shown the payment. No CoRyd service calls either endpoint;
the mobile apps only ever read their own.

## 8. Configuration and rollout

| Env (service) | Default | Meaning |
|---|---|---|
| `OPS_MFA_REQUIRED` (identity) | `false` | Every ops admin must use two-factor; unenrolled admins enroll at sign-in |
| `MFA_ENCRYPTION_KEY` (identity) | unset | AES-256-GCM key for the TOTP secrets (32 bytes base64, or a passphrase). Unset: derived from `JWT_SECRET` under its own label |
| `OPS_ACCESS_TOKEN_MINUTES` (identity) | `15` | Ops console access-token lifetime |
| `OPS_SESSION_IDLE_MINUTES` (identity; ops reads `ops.session.idle-minutes` only as a fallback for `/me`) | `30` | Refresh-token idle limit |
| `OPS_SESSION_ABSOLUTE_HOURS` (identity) | `12` | Whole-session limit |

**Secrets at rest:** TOTP secrets are AES-GCM encrypted (`v1:` + base64(iv‖ciphertext)) and never logged; recovery codes,
mfa and enroll tokens are stored as SHA-256 only. **Set `MFA_ENCRYPTION_KEY` before anyone enrolls and never change it** (or
`JWT_SECRET`, while it's unset): a changed key makes stored secrets unreadable and those admins need a super-admin reset.

**Rollout:**
1. Deploy with `OPS_MFA_REQUIRED=false` (default) and a `MFA_ENCRYPTION_KEY` (`openssl rand -base64 32`). Nothing changes
   for anyone except shorter console sessions.
2. Ship the console's two-factor screens (§1–4). Admins turn it on from their settings.
3. Check the Admin team list: every active admin has `mfaEnabled: true` (or accept that the rest enroll at their next sign-in).
4. Set `OPS_MFA_REQUIRED=true` on identity-service and restart it. Sessions already open carry on until they time out
   (at most 12 h); every new sign-in then needs a code, and anyone not enrolled is taken through enrollment.
5. Lost phone: the admin signs in with a recovery code, or a super admin resets their two-factor.

**At deploy:** ops access tokens issued before this keep their 24 h until they expire; an older 30-day ops refresh token
starts a 12-hour session at its first refresh. A stream token minted before deploy is judged by its own issue time.

## 9. Migrations

- `coryd:140` `identity.users`: `mfa_secret`, `mfa_enabled_at`, `mfa_pending_secret`, `mfa_pending_at`, `mfa_last_step`.
- `coryd:141` `identity.mfa_recovery_codes` (hash, used_at).
- `coryd:142` `identity.mfa_challenges` (mfa/enroll token hash, purpose, expiry, attempts, used_at).
- `coryd:143` `identity.refresh_tokens.session_started_at`.

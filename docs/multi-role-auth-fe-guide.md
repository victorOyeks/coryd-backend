# Rider + Driver Roles — FE Implementation Guide

Base path `/identity` · port `9101` · every endpoint below is under `/identity/auth/...`

This covers signup, login, and everything to do with a user being a Rider, a Driver, or both. If you're building the "Switch to Driver mode" button, or wondering why login sometimes fails with the right password, read it top to bottom.

---

## The one idea you need before anything else

**There is no such thing as a separate Rider account and Driver account.**

One phone number means one account. That account holds a **set of roles** — `RIDER`, `DRIVER`, or both. What changes between "rider mode" and "driver mode" is not the account, it's the **token**: every JWT is scoped to exactly one `activeRole`.

So "I want a driver account too" is never a signup. It's *adding a role to the account you're already logged into*, which hands you a new token scoped to that role.

Three consequences to design around:

- **Signup will reject a phone number that already has a finished account.** Don't route an existing user there, ever.
- **The user is never in both modes at once.** One `activeRole` per token.
- **Every auth response tells you the full picture** — read `activeRole` and `roles` from the response rather than tracking role in local state.

---

## The response you get from everything

`signup` aside, every call in this guide returns the same `AuthResponse`:

```json
{
  "success": true,
  "data": {
    "userId": "ff293fca-6f29-4033-ac38-46942aa66ec9",
    "fullName": "Victor",
    "firstName": "Victor",
    "lastName": "Oyekanmi",
    "phone": "+2349035911994",
    "activeRole": "RIDER",
    "roles": ["RIDER", "DRIVER"],
    "accessToken": "eyJhbGciOi...",
    "expiresIn": 86400,
    "refreshToken": "83f1c0de-...",
    "refreshExpiresIn": 2592000
  }
}
```

- **`fullName`** — despite the key, this is the **first name only** (`"Victor"`, not `"Victor Oyekanmi"`). The key is kept for clients already bound to it.
- **`firstName` / `lastName`** — the name as two fields, which is how it is now stored everywhere. Prefer these. Greet with `firstName`; join the two when you need the whole name. `lastName` is null for an account that only ever gave one name.
- **`activeRole`** — what this token is scoped to. Drive your UI mode off this.
- **`roles`** — everything the account holds. Show the "Switch to Driver" button only when `DRIVER` is missing from this; show a mode toggle when both are present.
- **Always replace both tokens.** Every endpoint here mints a fresh pair and the old access token keeps its old `activeRole`, so reusing it silently leaves you in the wrong mode.

`expiresIn` is seconds (24h), `refreshExpiresIn` is seconds (30 days).

---

## Flow 1 — Brand new user

```
POST /auth/signup        { "phone": "+2349035911994", "role": "RIDER" }
POST /auth/verify-otp    { "phone": "+2349035911994", "code": "483920" }
POST /auth/set-password  { "password": "..." }        ← authenticated
```

`role` accepts `RIDER` or `DRIVER` and defaults to `RIDER` if omitted. `OPS_ADMIN` is rejected.

Two things that surprise people:

**`verify-otp` already returns a full `AuthResponse`** — the user is authenticated *before* they have a password. Store the token from that response and send it as a Bearer token on `set-password`. Don't build a screen that assumes you're anonymous until the password is set.

**OTPs expire after 5 minutes.** `POST /auth/resend-otp { phone, channel }` issues a new one; `channel` is `SMS` or `WHATSAPP`.

If the user abandons signup partway, calling `signup` again with the same phone resumes it rather than erroring. Only a *fully onboarded* phone (verified **and** password set) is rejected:

> `An account with this phone number already exists - please log in`

---

## Flow 2 — Logging in

```
POST /auth/login    { "phone": "...", "password": "...", "role": "RIDER" }
```

`role` is which mode to log into. **Omit it and it defaults to `RIDER`.** If you're building the driver app entry point, send `"role": "DRIVER"` explicitly.

### The failure case you must handle

If the password is right but the account doesn't hold that role, you get **HTTP 400** with a structured body:

```json
{
  "success": false,
  "message": "You don't have a DRIVER account - please sign up first",
  "data": {
    "code": "ROLE_NOT_HELD",
    "heldRoles": ["RIDER"]
  }
}
```

**Branch on `data.code === "ROLE_NOT_HELD"`.** This is not a wrong password — it's the right person in the wrong door, and `heldRoles` tells you which doors are open.

Correct handling:

> "This number is registered as a Rider. Sign in as a Rider and you can add a Driver account from there."

Then log them in with `role: "RIDER"` and run Flow 3.

**Do not send them to signup.** Signup rejects the phone, and they end up stuck in a loop with no way forward. This is the single most common integration mistake on this API.

Any other 400 from login (`Incorrect phone number or password`) is a genuine credential failure — treat it normally.

---

## Flow 3 — Existing user adds the other role

The user is **already logged in**. There is no OTP and no password re-entry; the JWT already proves who they are.

```
POST /auth/roles/add    { "role": "DRIVER" }      ← authenticated
```

Returns a fresh `AuthResponse` with `activeRole: "DRIVER"` and `roles: ["RIDER", "DRIVER"]`. Swap both tokens and you're in driver mode immediately.

Errors:

| Response | Meaning |
|---|---|
| `You already have a DRIVER account` | Use `roles/switch` instead |
| `OPS_ADMIN cannot be self-assigned` | Don't send `OPS_ADMIN` |

After this, the user has the *role* but not the *setup* — a new driver still has no driver profile, no vehicle, and no approval. Send them into driver onboarding next, and see **Onboarding status** below.

---

## Flow 4 — Switching between roles they already hold

```
POST /auth/roles/switch    { "role": "RIDER" }    ← authenticated
```

Identical request shape to `roles/add`, completely different meaning: this grants nothing, it just re-scopes the session to a role the account already has. No password, no OTP.

If they don't hold the role you get `You don't have a RIDER account - add it first`.

### Picking between add and switch

```
roles.includes(target) ? roles/switch : roles/add
```

Calling the wrong one 400s in both directions, so drive it off the `roles` array from the last response rather than guessing.

---

## Flow 5 — Refreshing a session

```
POST /auth/refresh-token    { "refreshToken": "..." }
```

Returns a new `AuthResponse`. **The refresh token is single-use** — it's revoked the moment it's used and a new one comes back, so persist the new value every time.

The refreshed token keeps the **same `activeRole`** as the session it came from. Refreshing does not switch modes.

---

## Onboarding status

```
GET /auth/onboarding-status     ← authenticated
```

```json
{
  "data": {
    "phoneVerified": true,
    "passwordSet": true,
    "profileComplete": false,
    "termsAccepted": true
  }
}
```

**`profileComplete` is evaluated against the token's current `activeRole`.** After `roles/add`, it correctly flips back to `false` for the newly added role — that's what drives the new role's onboarding screens.

> **Known gap.** For drivers, `profileComplete` only checks that a driver profile row exists. It does **not** check that a vehicle is registered or approved. A driver can see `profileComplete: true` and still be unable to accept passengers. Until that's tightened, gate driver "go online" on the vehicle's verification status from profile-service, not on this flag.

`POST /auth/accept-terms` (authenticated, no body) sets `termsAccepted`.

---

## Other endpoints you'll want

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /auth/me` | ✅ | `userId`, `fullName`, `firstName`, `lastName`, `phone` — note `fullName` here is the **full** name, unlike the auth response. Only place a user's own phone is available |
| `POST /auth/change-password` | ✅ | `{ currentPassword, newPassword, confirmPassword }` |
| `POST /auth/forgot-password` | ✖ | `{ phone }` — always returns success, even for an unknown number, so it can't be used to probe which numbers are registered |
| `POST /auth/reset-password` | ✖ | `{ phone, code, newPassword, confirmPassword }` — OTP-based. **Revokes every existing session** on success, so the user is logged out everywhere and must sign in again |

**Password rules: minimum 8, maximum 72 characters.** Validate both client-side — 72 is BCrypt's own input limit, not an arbitrary choice.

---

## Decision table

| Situation | Call |
|---|---|
| New user | `signup` → `verify-otp` → `set-password` |
| Returning user, has the role | `login` with `role` |
| Returning user, lacks the role | `login` → `ROLE_NOT_HELD` → `login` with a held role → `roles/add` |
| Logged in, wants a role they lack | `roles/add` |
| Logged in, toggling a role they hold | `roles/switch` |
| Token expired | `refresh-token` |

---

## Checklist before you ship

- [ ] `ROLE_NOT_HELD` is handled as its own case, and never routes the user to signup
- [ ] Both `accessToken` and `refreshToken` are replaced after **every** call in this guide
- [ ] UI mode is driven by `activeRole` from the last response, not local state
- [ ] "Add driver account" vs "switch mode" is chosen from the `roles` array
- [ ] The new refresh token is persisted after every refresh (the old one is dead)
- [ ] OTP screens handle 5-minute expiry with a resend path
- [ ] Driver "go online" is gated on vehicle approval, not on `profileComplete`

---

## Dev environment only

In `dev` and `test` profiles, OTP code **`000333`** is accepted alongside the real code, so you can complete signup without access to the phone. The real randomly-generated code always works too. This bypass does not exist in production.

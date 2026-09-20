# Fingerprint Sign-In — FE Implementation Guide

Base path `/identity` · port `9101` · every endpoint below is under `/identity/auth/...`

This covers fingerprint (and Face ID) sign-in end to end. Most of the work is yours — read the first section before you plan anything, because it's the part people usually get backwards.

---

## The one idea you need before anything else

**The fingerprint never leaves the phone, and the backend never sees it.**

iOS and Android expose no API for reading a fingerprint image or template. There is nothing to send us and nothing for us to verify. If you find yourself designing `POST /auth/biometric-login { fingerprint: ... }`, stop — that endpoint cannot exist on any platform.

What biometrics actually do is **unlock a secret already stored on the device**. Concretely:

1. At normal sign-in (password or OTP), we hand you a `refreshToken`.
2. You store it in the **iOS Keychain** or **Android Keystore**, marked as requiring biometric authentication to read.
3. Next time the app opens, the fingerprint prompt unlocks that entry.
4. You call `POST /auth/refresh-token` with the token you just unlocked and get a fresh session.

That's the whole feature. The fingerprint is a **local gate on local storage**. Our side of the deal is making sure that unlocked token only works on *your* device and can be killed remotely if the phone is lost.

**Consequence for your UI:** biometric sign-in is not a sign-in *method* on the backend. It's a faster path to a session the user already established. A user must sign in with a password at least once before biometrics can be offered.

---

## What changed on the backend

Three things, all backwards compatible. Nothing below breaks your current build if you ignore it — but you need all of it for biometrics to be safe.

| Change | What it means for you |
|---|---|
| Sessions can be **bound to a device** | Send `device` on login/verify-otp, then send `deviceId` on every refresh |
| Refresh tokens now have a **60s retry grace window** | A dropped refresh response no longer signs the user out |
| New **device management** endpoints | Build the "Where you're signed in" screen |

---

## Step 1 — Generate a device ID

Create a **random UUID once per install**, store it in ordinary app storage (`UserDefaults` / `SharedPreferences`), and reuse it forever.

```
deviceId = "b4e1f0a2-9c3d-4e5f-8a7b-1c2d3e4f5a6b"   // generated once, never changes
```

Rules:

- **Do not use the IMEI, advertising ID, or `identifierForVendor`.** They're privileged, they get you rejected from app review, and the binding doesn't need them.
- **Do not put it in the Keychain.** It isn't a secret. If it's behind the biometric gate you can't send it on the calls that need it.
- It's fine that reinstalling the app produces a new one — that just registers as a new device.

---

## Step 2 — Send the device on sign-in

`POST /auth/login` and `POST /auth/verify-otp` both accept an optional `device` object:

```json
{
  "phone": "+2349035911994",
  "password": "...",
  "role": "RIDER",
  "device": {
    "deviceId": "b4e1f0a2-9c3d-4e5f-8a7b-1c2d3e4f5a6b",
    "deviceName": "Victor's iPhone",
    "platform": "IOS"
  }
}
```

- `platform` — `IOS`, `ANDROID` or `WEB`.
- `deviceName` — purely cosmetic, shown on the devices screen. On iOS use `UIDevice.current.name`; on Android `Settings.Global.DEVICE_NAME` or the model name.
- The whole `device` object is optional. **Omit it and you get an unbound session, exactly as today.**

**Send it on every sign-in, even if the user hasn't enabled biometrics.** Binding costs nothing and it's what makes "sign out my lost phone" work at all.

The response is the unchanged `AuthResponse` — no new fields.

---

## Step 3 — Store the refresh token behind the biometric gate

This is the FE-only part, and it's where the real work is.

**iOS** — Keychain item with `SecAccessControlCreateWithFlags` using `.biometryCurrentSet` and `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`.

**Android** — `EncryptedSharedPreferences`, or a Keystore key with `setUserAuthenticationRequired(true)`, gated by `BiometricPrompt`.

Two flags matter more than they look:

- **iOS `.biometryCurrentSet`** (vs `.biometryAny`) — invalidates the item when the user adds or removes a fingerprint. Use it. Otherwise someone who can add their own fingerprint to an unlocked phone inherits the account.
- **Android `setInvalidatedByBiometricEnrollment(true)`** — same reasoning, and it's the default on API 24+.

**Plan for the key disappearing.** Both platforms deliberately destroy the stored credential when biometric enrolment changes. This is not an error state to log and swallow — it's expected behaviour, and it will happen to real users. When the read fails, clear your local state, turn the biometric flag off, and route to password sign-in with a plain message ("Your fingerprint settings changed — please sign in again"). Do **not** show a crash or a generic error.

**Always keep a fallback.** Fingerprint fails for wet hands, scarred fingers, cold weather, and screen protectors. The password screen must always be one tap away, and it must not look like a punishment.

---

## Step 4 — Refresh with the device ID

`POST /auth/refresh-token` now takes a `deviceId`:

```json
{
  "refreshToken": "83f1c0de-...",
  "deviceId": "b4e1f0a2-9c3d-4e5f-8a7b-1c2d3e4f5a6b"
}
```

**If the token was issued with a device, `deviceId` is required and must match.** Sending the wrong one — or omitting it — revokes the entire session family and returns `400`. That's deliberate: if "absent" were treated as "matches", the binding would be worth nothing to anyone holding a stolen token.

Tokens issued *before* this change, and sessions where you didn't send a `device`, have no binding and keep working without `deviceId`.

---

## Step 5 — Handle refresh failures correctly

This matters more than usual with biometrics, because **the user will blame the fingerprint for anything that goes wrong here.**

`POST /auth/refresh-token` fails with `400` and one of these messages:

| Message | What happened | What you do |
|---|---|---|
| `Invalid or expired refresh token` | Token unknown to us | Clear stored credential, go to password sign-in |
| `Refresh token has expired` | 30 days elapsed | Same — this is normal, not an error |
| `This session is no longer valid - please sign in again` | Token reuse or device mismatch detected; **all** sessions for that sign-in are now dead | Clear stored credential, go to password sign-in |
| `This device has been signed out - please sign in again` | User revoked this device from another phone | Clear stored credential **and** the biometric flag, go to password sign-in |

In every case the recovery is the same: **wipe the Keychain/Keystore entry and send the user to password sign-in.** Never retry a failed refresh with the same token — on the reuse path, retrying is exactly what an attacker would do.

### The 60-second grace window — what it buys you

Refresh tokens rotate: each call returns a *new* `refreshToken` and the old one stops working. That used to mean a lost response destroyed the only credential the app had, and on a Nigerian mobile connection that's routine, not rare.

So: **if you present a token that was rotated less than 60 seconds ago, you get the same replacement back again** rather than an error. The call is idempotent inside that window.

What this means for you:

- **Safe to retry a refresh that timed out or failed at the network layer**, as long as you retry promptly. You'll get a usable session back.
- **Always persist the new `refreshToken` immediately** on a successful refresh — before you do anything else with the response. If you write it late and the app is killed in between, you fall outside the window on next launch and the user gets signed out.
- Do not retry after a `400`. A `400` is a decision, not a glitch.

---

## Step 6 — Record the user's biometric preference

```
POST /auth/devices/biometric        ← authenticated
{ "deviceId": "b4e1f0a2-...", "enabled": true }
```

**This is bookkeeping, not enforcement.** It grants nothing and unlocks nothing — we can't check a fingerprint, so this call can't prove one happened. It exists so the devices screen can tell the user which of their phones they left fingerprint sign-in enabled on.

Call it when the user flips the toggle in settings. Don't gate anything on it, and don't call it as part of the sign-in flow.

---

## Step 7 — The "Where you're signed in" screen

```
GET    /auth/devices                ← authenticated, send X-Device-Id header
DELETE /auth/devices/{deviceId}     ← authenticated
```

`GET /auth/devices` returns:

```json
{
  "success": true,
  "data": [
    {
      "deviceId": "b4e1f0a2-...",
      "deviceName": "Victor's iPhone",
      "platform": "IOS",
      "biometricEnabled": true,
      "lastSeenAt": "2026-09-20T09:14:02Z",
      "current": true
    }
  ]
}
```

Pass your device id in the **`X-Device-Id` header** so we can mark `current: true` on the row the user is looking at from. Label it "This device" and warn before they sign it out — deleting the current device logs them out immediately.

`DELETE /auth/devices/{deviceId}` revokes the device and kills every refresh token bound to it. This is the lost-phone flow, and it's the whole reason device binding exists: without it there'd be nothing to revoke, because an unbound token works from anywhere.

---

## Role switching

`POST /auth/roles/add` and `POST /auth/roles/switch` now accept an optional `deviceId`:

```json
{ "role": "DRIVER", "deviceId": "b4e1f0a2-..." }
```

**Send it.** Both endpoints mint a fresh session, and without it a bound session silently downgrades to an unbound one every time the user toggles between rider and driver mode.

---

## Putting it together

**First-ever sign-in**
```
login (with device) → store refreshToken behind biometric gate
                    → POST /auth/devices/biometric { enabled: true }
```

**App launch, biometrics on**
```
BiometricPrompt → unlock Keychain → refresh-token (with deviceId)
                                  → persist new refreshToken IMMEDIATELY
                                  → into the app
```

**Biometric prompt cancelled or failed** → password screen. Keep the stored credential; nothing is wrong with it.

**Refresh returns 400** → wipe stored credential, password screen.

**User taps "sign out this device" on another phone** → this device's next refresh returns `This device has been signed out`. Wipe and go to password sign-in.

---

## Checklist

- [ ] Device UUID generated once, in plain storage, never in the Keychain
- [ ] `device` sent on login and verify-otp
- [ ] `deviceId` sent on refresh-token, roles/add, roles/switch
- [ ] `X-Device-Id` header on `GET /auth/devices`
- [ ] Refresh token stored with `.biometryCurrentSet` / `setInvalidatedByBiometricEnrollment`
- [ ] New refresh token persisted *immediately* on every successful refresh
- [ ] Biometric-enrolment-changed handled as a normal path, not an error
- [ ] Password fallback reachable from every biometric prompt
- [ ] All four `400` messages route to wipe-and-re-authenticate
- [ ] Devices screen marks `current` and warns before self-revoke

---

## Questions we've already been asked

**"Can we skip the password and let users sign up with just a fingerprint?"**
No. A fingerprint only unlocks a credential that already exists — there's nothing to unlock until the user has authenticated once the normal way.

**"Can the backend tell us whether the user's fingerprint matched?"**
No. We never see it. `biometricEnabled` is the user's stated preference, not evidence.

**"The user changed phones. Do they lose access?"**
They sign in with their password on the new phone and enable biometrics there. The old device stays in their devices list until they revoke it — prompt them to.

**"Why does the refresh token keep changing?"**
Rotation. It's what makes a stolen token detectable: if two parties hold the same token, one of them will eventually present a spent one and we can kill the whole session family. Persist the new one every time.

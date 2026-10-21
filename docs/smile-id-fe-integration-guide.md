# FE guide: Smile ID identity and licence checks

Everything the rider app, driver app and Ops Console need for the Smile ID integration. It replaces the NIN + selfie upload and the licence photo upload with Smile ID's SDK. The backend is live on `develop`.

All paths go through the gateway (`https://api-dev.coryd.ng` on dev) with the usual `Authorization: Bearer <accessToken>` and the usual envelope `{ success, message, data }`. Error `message`s can be shown as-is.

## At a glance

| Check | Who | What the user does | Smile ID product |
| --- | --- | --- | --- |
| **NIN** | Riders and drivers, once per person | Enters their NIN, takes a live selfie | Biometric KYC: NIN looked up with NIMC, selfie checked for liveness and matched to the NIN photo |
| **Driver's licence** | Drivers | Photographs their licence, takes a live selfie | Document Verification: licence checked as genuine and read, selfie matched to the licence photo |

Both checks work the same way:

```
App                                       CoRyd backend                      Smile ID
 │ POST …/session ───────────────────────▶ opens a check, gets a token ────▶│
 │ ◀── { checkId, token, partnerId, sandbox, callbackUrl,                    │
 │       country, idType, partnerParams }                                    │
 │ Smile ID SDK: consent, capture, submit ──────────────────────────────────▶│
 │ onResult → poll the status endpoint every 3 s                             │
 │                                         │◀──── result (signed webhook) ───│
 │ ◀── status / retry reason               │                                 │
```

- **Images:** the selfie and licence photo go **straight from the SDK to Smile ID**; our backend never receives them.
- **Keys:** the app holds **no Smile ID keys**. Each check gets a short-lived token from our backend.
- **Who rejects:** only ops ever rejects. An automatic failure the person can fix comes back as a **retry reason**; anything doubtful goes to ops.

## 1. Setup (both apps)

Smile ID's current SDK is **v12**. Don't use anything from the older SDK: `@smile_identity/react-native-expo`, `smile_config.json`, `SmileID.initialize()`, `SmileIDBiometricKYCView`, or `ResultCode` values like `0810` are v10/v11, and Smile ID says v11 code won't compile against v12.

- **Requirements:**
  - **Expo SDK 56+** (React Native 0.83+), iOS 16.4+, Android `minSdk` 24 and `compileSdk` 36. The app is on Expo 54, so **upgrade first**.
  - It's a native module: it needs a new build, not an over-the-air update, and doesn't run in Expo Go.
- **Install**, following [Smile ID's Expo setup](https://docs.usesmileid.com/developer-resources/sdks/mobile/setup), with the face **and** document providers (the licence check needs both):
  ```sh
  pnpm add @smileid/usesmileid \
           @smileid/usesmileid_mlkit_face @smileid/usesmileid_mlkit_document \
           @smileid/usesmileid_vision_face @smileid/usesmileid_vision_document \
           react-native-quick-crypto react-native-quick-base64 react-native-nitro-modules \
           react-native-safe-area-context react-native-svg lottie-react-native
  npx expo install expo-brightness expo-file-system expo-font expo-haptics \
                   expo-image-manipulator expo-image-picker expo-keep-awake expo-screen-orientation
  ```
  Then add the ML Kit and Vision config plugins to `app.json` and the camera permission, as in their setup page.
- **Providers**, as platform-suffix files. Importing the wrong platform's package crashes the bundle.
  ```ts
  // src/providers.ios.ts
  import { useSmileIDVisionFace } from "@smileid/usesmileid_vision_face";
  import { useSmileIDVisionDocument } from "@smileid/usesmileid_vision_document";
  export const faceAnalyzer = useSmileIDVisionFace;
  export const documentAnalyzer = useSmileIDVisionDocument;

  // src/providers.android.ts
  import { useSmileIDMlkitFace } from "@smileid/usesmileid_mlkit_face";
  import { useSmileIDMlkitDocument } from "@smileid/usesmileid_mlkit_document";
  export const faceAnalyzer = useSmileIDMlkitFace;
  export const documentAnalyzer = useSmileIDMlkitDocument;
  ```
- **Sandbox on dev, live in production. The backend decides; the app never does.** Always pass the session's `sandbox`, `userDetails` and the rest through unchanged. See [Testing on dev](#8-testing-on-dev-sandbox).

### What the app passes to the SDK

Both `/session` endpoints return the same shape:

```json
{
  "checkId": "coryd-nin-5c1a…",
  "token": "<short-lived>",
  "partnerId": "1234",
  "sandbox": false,
  "callbackUrl": null,
  "country": "NG",
  "idType": "NIN_V2",
  "partnerParams": { "coryd_check_id": "coryd-nin-5c1a…" },
  "userDetails": { "givenNames": "Grace", "lastName": "Amanda", "email": null }
}
```

| SDK setting | Value |
| --- | --- |
| `network.config.token` | `token` |
| `network.config.onTokenExpired` | Call the same `/session` endpoint again and return its `token`. That opens a fresh check, so also swap in its `partnerParams`; the old check is simply ignored. |
| `partnerConfig.partnerId` | `partnerId` |
| `partnerConfig.useSandbox` | `sandbox` |
| `partnerConfig.callbackUrl` | `callbackUrl`, **only if not null**. Leave it unset otherwise; the portal's URL is used. |
| `partnerConfig.partnerParams` | `partnerParams` **exactly as given.** This is how the result finds its way back to the person. Without it, the check never completes. |
| `userDetails` | `userDetails` **exactly as given**, minus any null `email`. The SDK requires it. In production it's the person's own name; on dev it's Smile ID's test identity (see section 8). |
| `userId` / `jobId` | **Don't set them.** Smile ID issues its own. |

Start the SDK **straight away**: the token is short-lived.

## 2. NIN check (riders and drivers)

One check per person: someone who is both a rider and a driver verifies once, in whichever app they onboard first.

### Flow

1. **Name first.** First and last name, as on the NIN, saved on the profile for the role they're in: `PUT /profile/riders/me` or `PUT /profile/drivers/me`.
2. **NIN + consent screen:** the consent text says the NIN and selfie are checked with NIMC through our verification provider.
3. **Open the check:** `POST /profile/identity-verification/session`
   ```json
   { "nin": "12345678901", "consent": true }
   ```
4. **Run the SDK** as a Biometric KYC job:
   ```tsx
   import { CaptureType, JobType, UseSmileIDBuilder } from "@smileid/usesmileid";
   import { faceAnalyzer } from "./src/providers";

   <UseSmileIDBuilder
     builder={(b) => {
       b.network((n) => n.config((c) => {
         c.jobType = JobType.biometricKyc;
         c.token = session.token;
         c.onTokenExpired = async () => (await reopenSession()).token;
         c.partnerConfig((p) => {
           p.partnerId = session.partnerId;
           p.useSandbox = session.sandbox;
           if (session.callbackUrl) p.callbackUrl = session.callbackUrl;
           p.partnerParams = session.partnerParams;
         });
       }));
       b.ml((m) => m.analyzers((a) => a.forCaptureType(CaptureType.selfie, (f) => f.add(faceAnalyzer))));
       b.screens((s) => {
         s.consent((c) => { c.partnerName = "CoRyd"; c.partnerPrivacyPolicyUrl = "https://coryd.ng/privacy"; });
         s.instructions();
         s.capture((cap) => { cap.captureType = CaptureType.selfie; });
         s.preview();
         s.processing();
       });
       b.userDetails = withoutNulls(session.userDetails);
       b.biometricKYCParams = { country: session.country, idType: session.idType, idNumber: nin };
       b.onResult = onSmileIdResult;
     }}
   />
   ```
5. **On `onResult`:**
   - `success`: poll `GET /profile/identity-verification/me` every 3 s until `checkInProgress` is `false`. After about 2 minutes, show "We're checking your details" and refresh when the screen opens.
   - `cancelled`: back to the NIN screen. Nothing was submitted, and no try is used.
   - `failure`: show *"Something went wrong - please try again"* and let them retry from step 3.

   **Don't tear the SDK view down early.** Wait for `onResult`; on iOS it fires only after the user dismisses the last screen.

### Status: `GET /profile/identity-verification/me`

```json
{
  "userId": "…", "status": "PENDING", "ninLast4": "8901", "submittedAt": "…", "decidedAt": null,
  "rejectionReason": null, "checkInProgress": false, "retryReason": "FACE_MISMATCH", "attemptsLeft": 4
}
```

| State | Show |
| --- | --- |
| `APPROVED` | Verified. Continue; booking (riders) and posting routes (drivers) are open. |
| `PENDING` + `checkInProgress: true` | "Checking your details…" |
| `PENDING` + `retryReason` set | The [retry message](#4-retry-reasons-both-checks) and a **Try again** button (back to step 3) |
| `PENDING` with neither | Not done yet, or they left mid-check: the NIN screen. **Onboarding isn't finished.** |
| `IN_REVIEW` | "Our team is checking your details." The rest of the app can be browsed; booking and posting stay closed. |
| `REJECTED` | `rejectionReason` and a **Try again** button |

`GET /identity/auth/onboarding-status` (`identityVerified`, `identityVerificationStatus`) and the booking and posting gates are unchanged. Read `/me` to tell "checking" and "try again" apart from "not started".

### Errors from `/session`

| Error | When |
| --- | --- |
| `400` *"NIN must be 11 digits"* | Bad NIN format |
| `400` *"Agree to the identity check to continue"* | `consent` not `true` |
| `400` *"Save your profile before verifying your identity"* | No profile for this role |
| `400` *"Add your first and last name, as on your NIN, before verifying"* | Name missing |
| `400` *"Your identity is already verified"* | Already approved |
| `400` *"Your identity check is with our team - we'll let you know once it's done"* | `IN_REVIEW` |
| `400` *"Too many attempts - contact CoRyd support to verify your identity"* | 5 tries used. Show a contact-support screen. |
| `400` *"Identity checks are unavailable right now - try again later"* | Smile ID isn't set up on this environment yet |
| `502` *"Identity checks are unavailable right now - try again in a few minutes"* | Smile ID didn't issue a token |

The full NIN is never stored or returned; only `ninLast4`.

## 3. Driver's licence check (drivers)

This replaces uploading a licence photo for the `GOVERNMENT_ID` verification item. If the licence is genuine, in the driver's name, with the number they entered, not expired, and their live face is on it, it's **approved automatically**, exactly as if ops had approved it. The driver gets the usual "item approved" notification and goes live if it was the last item. Anything else goes to the ops queue.

It isn't an FRSC lookup; Smile ID can't do one in Nigeria. Vehicle, insurance and roadworthiness are unchanged.

### Flow

1. **Profile first** (as today): `PUT /profile/drivers/me` with `firstName`, `lastName`, `idNumber` (the licence number) and `dateOfBirth`, as on the licence. Leave out `governmentIdUrl`.
2. **Open the check:** `POST /profile/drivers/me/licence-check/session`. No body; same response shape, with `idType: "DRIVERS_LICENSE"`.
3. **Run the SDK** as a Document Verification job:
   ```tsx
   import { CaptureType, DocumentCaptureMode, JobType, UseSmileIDBuilder } from "@smileid/usesmileid";
   import { documentAnalyzer, faceAnalyzer } from "./src/providers";

   <UseSmileIDBuilder
     builder={(b) => {
       b.network((n) => n.config((c) => {
         c.jobType = JobType.documentVerification;
         c.token = session.token;
         c.onTokenExpired = async () => (await reopenSession()).token;
         c.partnerConfig((p) => {
           p.partnerId = session.partnerId;
           p.useSandbox = session.sandbox;
           if (session.callbackUrl) p.callbackUrl = session.callbackUrl;
           p.partnerParams = session.partnerParams;
         });
       }));
       b.ml((m) => m.analyzers((a) => {
         a.forCaptureType(CaptureType.selfie, (f) => f.add(faceAnalyzer));
         a.forCaptureType(CaptureType.document, (d) => d.add(documentAnalyzer));
       }));
       b.screens((s) => {
         s.consent((c) => { c.partnerName = "CoRyd"; c.partnerPrivacyPolicyUrl = "https://coryd.ng/privacy"; });
         s.instructions();
         s.capture((cap) => {
           cap.captureType = CaptureType.document;
           cap.document((doc) => {
             doc.documentType = { kind: "genericDocument" };
             doc.captureMode = DocumentCaptureMode.AutoCaptureWithManualFallback();
             doc.captureBothSides = true;
             doc.allowGalleryUpload = false;
             doc.knownIdAspectRatio = 1.586;
           });
         });
         s.capture((cap) => { cap.captureType = CaptureType.selfie; });
         s.preview();
         s.processing();
       });
       b.userDetails = withoutNulls(session.userDetails);
       b.documentVerificationParams = { country: session.country, idType: session.idType };
       b.onResult = onSmileIdResult;
     }}
   />
   ```
   `allowGalleryUpload = false` keeps it to a live photo of the card, not a saved image.
4. **On `onResult`:** same as the NIN check, polling `GET /profile/drivers/me` until `licenceCheckInProgress` is `false`.

### Status: `GET /profile/drivers/me`

New fields alongside the existing `governmentIdStatus` / `governmentIdRejectionReason`:

```json
{ "governmentIdStatus": "PENDING", "licenceCheckInProgress": false, "licenceRetryReason": "UNREADABLE", "licenceAttemptsLeft": 5 }
```

| State | Show |
| --- | --- |
| `APPROVED` | Licence verified. |
| `PENDING` + `licenceCheckInProgress: true` | "Checking your licence…" |
| `PENDING` + `licenceRetryReason` set | The [retry message](#4-retry-reasons-both-checks) and **Try again** (back to step 2) |
| `PENDING` with neither | Not done yet, or they left mid-check: the licence screen |
| `IN_REVIEW` | "Our team is checking your licence." |
| `REJECTED` | `governmentIdRejectionReason` and **Try again**. Step 2 works after a rejection. |

`GET /profile/drivers/me/onboarding-status` and the rest of driver verification are unchanged.

### Errors from `/licence-check/session`

| Error | When |
| --- | --- |
| `400` *"Save your driver profile before verifying your licence"* | No driver profile |
| `400` *"Add your name and licence number, as on your licence, before verifying it"* | Name or licence number missing |
| `400` *"Your licence is already verified"* | Already approved |
| `400` *"Your licence is with our team - we'll let you know once it's checked"* | `IN_REVIEW` |
| `400` *"Too many attempts - contact CoRyd support to verify your licence"* | 5 tries used |
| `400` *"Licence checks are unavailable right now - try again later"* | Smile ID isn't set up on this environment yet |
| `502` *"Identity checks are unavailable right now - try again in a few minutes"* | Smile ID didn't issue a token |

## 4. Retry reasons (both checks)

`retryReason` (NIN) and `licenceRetryReason` (licence) use the same values. Each check has 5 tries; abandoning a check mid-way doesn't use one.

| Value | Check | Suggested message | Uses a try? |
| --- | --- | --- | --- |
| `FACE_MISMATCH` | Both | "Your selfie didn't match the photo on your NIN / licence. Try again in good light, facing the camera." | Yes |
| `NIN_NOT_FOUND` | NIN | "We couldn't find that NIN. Check the number and try again." | Yes |
| `NOT_A_LICENCE` | Licence | "That doesn't look like a Nigerian driver's licence. Photograph the front of your licence." | Yes |
| `UNREADABLE` | Both | "We couldn't read your photo. Try again in good light, with no glare, and fill the frame." | No |
| `TRY_LATER` | Both | "We couldn't finish the check just now. Please try again in a few minutes." | No |

An abandoned check (app closed mid-capture) stops showing as in progress after 15 minutes, and the person just starts again.

## 5. Older endpoints (until everyone has the new build)

These still work so current app builds don't break. Everything they submit is **reviewed by ops by eye**; nothing is checked automatically. New code should use the `/session` endpoints.

| Older call | What it does now |
| --- | --- |
| `POST /profile/identity-verification` `{ nin, selfieUrl, selfiePublicId, consent }` | NIN + uploaded selfie → `IN_REVIEW` for ops |
| `PUT /profile/drivers/me` with `governmentIdUrl` + `governmentIdPublicId` | Licence photo → `IN_REVIEW` for ops |

They'll be removed once every install is on the Smile ID build.

## 6. Ops Console

### Identity queue: `GET /ops/identity-verification/queue`

**Breaking:** `premblyOutcome` / `premblySummary` are renamed to **`checkOutcome`** / **`checkSummary`**.

```json
{
  "userId": "…", "firstName": "Chidi", "lastName": "Okafor", "ninLast4": "8901", "selfieUrl": null,
  "submittedAt": "…", "attempts": 0, "checkOutcome": "NAME_MISMATCH",
  "checkSummary": "NIN is held by TUNDE BAKARE, profile says Chidi Okafor - Smile ID job job_01m3…"
}
```

- **The selfie isn't on our servers.** Open the Smile ID portal and search the job ID at the end of `checkSummary`. `selfieUrl` is only set for the older upload.
- **Another account:** a note *"Same ID or face as another account: <userId> …"* means Smile ID has seen this NIN or face on another CoRyd account.
- **Decisions** (`POST /ops/identity-verification/{userId}/decision`) are unchanged.

### Driver verification: licence card

- **Arrival:** a licence Smile ID couldn't approve arrives in the driver queue as `GOVERNMENT_ID` `IN_REVIEW`, like an upload did.
- **Why it's there:** on the review page (`GET /ops/drivers/{driverId}/verification`), show the latest **`DRIVERS_LICENCE`** entry from `providerChecks` beside the licence card. Its `summary` says what didn't match (name, number, expiry, forgery or photocopy flag) and ends with the Smile ID job ID.
- **The licence image:** `governmentId.url` is null for Smile ID checks; link ops to the Smile ID portal with the job ID instead.
- **Decisions** are unchanged.

| `outcome` | Meaning | Badge |
| --- | --- | --- |
| `MATCH` | Verified (you'll mostly see this on approved drivers) | green |
| `NAME_MISMATCH` | The NIN or licence is in someone else's name | amber |
| `DETAILS_MISMATCH` | Number or expiry wrong, face mismatch, or a forgery, photocopy, spoof or risk flag | amber |
| `NOT_FOUND` | NIN not found, or not a licence | red |
| `PROVIDER_ERROR` | Smile ID couldn't finish; not the person's fault | grey "Check unavailable" |

## 7. Checklist

**Both apps**
- [ ] Expo SDK 56+ upgrade, then `@smileid/usesmileid` v12 with the face and document providers (new native build)
- [ ] Shared Smile ID launcher: token, `onTokenExpired` → reopen session, `partnerId`, `useSandbox`, optional `callbackUrl`, **`partnerParams` and `userDetails` as given**
- [ ] NIN step: name check, NIN + consent, `POST /profile/identity-verification/session`, Biometric KYC flow, poll `GET …/identity-verification/me`
- [ ] Status screens, `retryReason` messages + Try again, contact-support screen at 0 tries left
- [ ] Existing users with `identityVerified: false` sent to the NIN step on app open (unchanged)

**Driver app**
- [ ] Licence step: `POST /profile/drivers/me/licence-check/session`, Document Verification flow (licence + selfie), poll `GET /profile/drivers/me`
- [ ] `licenceRetryReason` messages + Try again; `IN_REVIEW` and `REJECTED` screens
- [ ] Stop sending `governmentIdUrl` from the new build

**Ops Console**
- [ ] Identity queue: read `checkOutcome` / `checkSummary` (renamed)
- [ ] Licence card: show the latest `DRIVERS_LICENCE` provider check; with no image, link to the Smile ID portal by the job ID in the summary

## 8. Testing on dev (sandbox)

Dev runs on Smile ID's **sandbox**: free, no real NIMC lookups. Production runs on **live** with real data. Nothing in the app changes between the two; the backend switches it.

On dev, the backend sends **every** check to Smile ID as its test identity **Amina Fatou Clearwater** (`amina.clearwater@example.com`), the identity Smile ID always answers **clear** for. So on dev:
- **any** test account, with **any** 11-digit NIN, any selfie and any licence photo, comes back **approved** (both checks);
- the backend matches the result against that test identity, not the tester's profile, so a mismatched name never sends it to ops;
- notes in the ops queue are prefixed `[SANDBOX]`.
- checks can be **run again and again**: an account that's already verified (or with ops) can start a new check, with any NIN, and there's no 5-try limit. A verified account stays verified while the re-check runs (`checkInProgress` is `true`), then is approved again. Production keeps the one-check-per-person rule.

The SDK screens, camera, upload, polling and status screens all run for real; only Smile ID's verdict is fixed. Failure paths (face mismatch, NIN not found, and so on) are covered by the backend's automated tests. To see them in the app anyway, ask the backend team to switch the test identity temporarily, for example to `Twinley` for a face mismatch.

In production the app sends the person's real name and Smile ID checks their real NIN and licence; sandbox test identities are refused there.


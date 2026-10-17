# Smile ID identity check — setup

Smile ID runs two checks, both in profile-service and both through the same code (`SmileIdCheckService`):

| Check | Who | Smile ID product | What it proves | Where the outcome lives |
| --- | --- | --- | --- | --- |
| `NIN` | Everyone, once | Biometric KYC | The NIN exists with NIMC, the selfie is a live person, the face matches the NIN photo; we then match the NIMC name to the profile | `identity_verifications` (trip-service hears approvals) |
| `DRIVERS_LICENCE` | Drivers | Document Verification | The licence is genuine (security features, not a photocopy or screen), its photo matches a live selfie; we then match its name, number and expiry to the profile | `driver_profiles.government_id_status` (the `GOVERNMENT_ID` item) |

The licence check is **not an FRSC lookup**: Smile ID can't do one in Nigeria. Vehicle plate, insurance and roadworthiness are still reviewed by ops.

`profile.smile_id_checks` keeps one row per person per check. It holds the open job, when it started, the retry reason and tries used (5 per check). The outcomes themselves stay where they always were.

---

## How it works

```
App ── POST /profile/identity-verification/session          (NIN)     ──▶ profile-service ── POST /v3/token ──▶ Smile ID
    or POST /profile/drivers/me/licence-check/session       (licence)
App ◀── { checkId, token, partnerId, sandbox, callbackUrl, country, idType, partnerParams } ──
App ── Smile ID SDK: live selfie + NIN, or licence photo + live selfie ─────────────────────▶ Smile ID
profile-service ◀── POST /profile/webhooks/smile-id (signed result) ──────────────────────── Smile ID
```

1. **Session.** Checks the person may start a check:
   - NIN: profile name saved and consent given;
   - licence: driver profile with name and licence number;
   - both: not already verified, not with ops, under 5 tries.

   It then gets a short-lived token from Smile ID with our API key and opens a job. The status stays `PENDING`, so an abandoned check never leaves anyone stuck. A licence ops rejected goes back to `PENDING` here, so the driver can try again.
2. **Capture.** The app's Smile ID SDK captures the selfie (and the licence) and submits them to Smile ID directly. The images never reach our servers.
3. **Result.** Smile ID posts every result, for both checks, to `POST /profile/webhooks/smile-id`.
   - It's public, but nothing is read until the `Response-Signature` header checks out (HMAC-SHA256 with our API key).
   - Smile ID issues its own job and user IDs (v12 doesn't let the app choose them), so the app passes **our** check ID in the SDK's `partnerParams` (`coryd_check_id`) and Smile ID echoes it back. That ID is looked up in `smile_id_checks` (column `job_id`), which also says which check it is.
   - A result for anything other than the person's current job is ignored, and so is a repeat delivery or one arriving after ops took over or decided.

| Smile ID result | NIN | Licence |
| --- | --- | --- |
| `clear`, and what Smile ID read matches the person | **Approved** (`decidedBy: SMILE_ID`); trip-service unlocks booking/posting | **`GOVERNMENT_ID` approved**, exactly like an ops approval: the driver is notified and goes live if it was the last item |
| `clear`, but it doesn't match | Ops queue: NIMC name ≠ profile (`NAME_MISMATCH`) | Driver verification queue: name, number or expiry wrong |
| `block` `face_verification_failed` | Retry `FACE_MISMATCH`, uses a try | Same |
| `block` `identifier_not_found` | Retry `NIN_NOT_FOUND`, uses a try | — |
| `block` `unsupported_document` | — | Retry `NOT_A_LICENCE`, uses a try |
| `error` unusable images (`image_unavailable_or_invalid`, `document_unclassifiable`, `content_policy_violated`) | Retry `UNREADABLE`, no try used | Same |
| `error` "ID authority photo not available" | Ops queue: NIMC has no photo, so retrying can't help | — |
| `error` anything else | Retry `TRY_LATER`, no try used (unknown reasons logged as errors) | Same |
| `document_expired` | — | Driver verification queue |
| Everything else: spoof, `high_risk`, `document_check_failed` (forgery), `document_copy_detected`, `attention`, anything new | Ops queue | Driver verification queue |

Name matching accepts either name order. For the NIN, either of the person's profiles (rider or driver) counts.

**Nothing here rejects anyone.** Only ops rejects, with a reason the person sees.

**Smile Secure.** If Smile ID has seen the same NIN or face on another CoRyd account, the check's note in the ops queue says so and names that user.

**Audit trail.** Every result is kept on `profile.verification_checks`:
- `check_type` is `NIN_FACE` or `DRIVERS_LICENCE`, `subject_key` holds our check ID, and `provider_reference` holds Smile ID's job ID. Ops see the summary, which ends with Smile ID's job ID, on the identity queue or the driver review page.
- `raw_response` holds the full answer, with the selfie, NIMC photo and receipt links removed.
- `raw_response` includes the full NIN, so read it from the database only; it's never served by an API.
- The selfie itself stays in the Smile ID portal: search the job ID.

---

## Getting set up

1. **Account.** Get a Smile ID account and complete their business KYC (Kinetum Global Solutions Ltd, RC 9825799).
2. **Products.** Ask Smile ID to:
   - activate **Biometric KYC** and **Document Verification** for Nigeria (driver's licence);
   - confirm **`NIN_V2`** is enabled (it needs an NIMC enterprise ID on the account). If it isn't, set `SMILE_ID_ID_TYPE=NIN_SLIP`.
3. **Keys.** CoRyd runs on Smile ID's **live** environment only, dev server included. Once Smile ID approves the account and the wallet is funded, take the **partner ID** and create a **Production** API key (*Developers › Security Settings › Production › Create Credential*; Owner or Admin only). Live is prepaid: checks fail when the wallet is empty.
4. **Callback URL.** Set it under *Developer › Security Settings › Callback URLs › Production*:
   - dev server: `https://api-dev.coryd.ng/profile/webhooks/smile-id`
   - production server: the same path on the production API host
5. **Server env.** Add to the profile-service env and restart:

```bash
SMILE_ID_PARTNER_ID=<numeric partner id>
SMILE_ID_API_KEY=<production API key>
SMILE_ID_SANDBOX=false
SMILE_ID_CALLBACK_URL=           # blank = the portal's URL for this environment
SMILE_ID_ID_TYPE=NIN_V2
SMILE_ID_NAME_MATCH_THRESHOLD=0.85
```

A live key only works with `SMILE_ID_SANDBOX=false` (`api.smileidentity.com`); leaving it `true` points at the sandbox host. A mismatch shows up as a 502 *"Identity checks are unavailable…"* on `/session`, with a 401 from Smile ID in the profile-service log.

---

## Testing (live)

There's no sandbox: Smile ID refuses its test identities in live, so **every check is real and paid**. Test with the team's own NINs, licences and faces, and keep repeats to a minimum.

What you can see in live: approvals, face mismatches (a different person's selfie), name mismatches (a profile name that differs from the NIN or licence), and an expired licence if someone has one. The rarer outcomes (NIN not found, spoof, Smile ID outage, forged licence) are covered by the automated and end-to-end tests, which simulate Smile ID's signed results.

Check the webhook from the server log:
- *"NIN check coryd-nin-… opened for …"* / *"DRIVERS_LICENCE check coryd-dl-… opened for …"* when the app starts a check;
- *"Smile ID webhook with a missing or wrong signature"* if the API key doesn't match the one Smile ID signs with;
- *"… check coryd-… for …: APPROVE / RETRY / OPS - …"* when the result lands.

---

## Going live

1. **Keys and env.** Production API key, `SMILE_ID_SANDBOX=false`, then restart profile-service. Remove any old `PREMBLY_*` lines from the env.
2. **Callback URL.** Make sure the live environment's callback URL is set in the portal.
3. **People already approved** (by Prembly or ops) stay approved. Anyone `IN_REVIEW` from the older selfie flow stays in the ops queue.
4. **Older app builds.** The older uploads still work for builds without the SDK, and are reviewed by ops by eye: the selfie via `POST /profile/identity-verification`, the licence photo via `governmentIdUrl` on `PUT /profile/drivers/me`. Remove them once every install is on the Smile ID build.

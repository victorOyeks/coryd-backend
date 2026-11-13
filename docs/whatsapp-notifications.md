# WhatsApp notifications

notification-service can send some texts on WhatsApp instead of SMS. Termii, our SMS provider, sends them.
WhatsApp is **off by default**. When it's off, every message goes by SMS exactly as before, and the service
makes no WhatsApp call.

## What goes on WhatsApp

| Notification | Event type in `notification_log` | Sent to | Preference that applies | Template env key |
|---|---|---|---|---|
| OTP, when the user picks WhatsApp on resend (`OtpChannel.WHATSAPP`) | `OTP_ISSUED` | the user | none (the user chose WhatsApp) | `WHATSAPP_TEMPLATE_OTP` |
| Shared trip link | `TRIP_SHARE_REQUESTED` | each of the rider's emergency contacts | the rider's `whatsappOptIn` | `WHATSAPP_TEMPLATE_TRIP_SHARE` |
| SOS alert to emergency contacts | `SOS_TRIGGERED` | each emergency contact of whoever pressed SOS | that person's `whatsappOptIn` | `WHATSAPP_TEMPLATE_SOS_CONTACT` |
| SOS alert to ops | `SOS_OPS_ALERT` | each `SOS_OPS_PHONES` number | none | `WHATSAPP_TEMPLATE_SOS_OPS` |
| Unanswered "Are you OK?" check | `SAFETY_ESCALATED` | each `SOS_OPS_PHONES` number | none | `WHATSAPP_TEMPLATE_SAFETY_ESCALATED` |
| SOS / unanswered check still unacknowledged in the Ops Console (5, 15, 30 min) | `SOS_OPS_REALERT` | each `SOS_OPS_PHONES` number | none | `WHATSAPP_TEMPLATE_SOS_OPS` (same template; `who` reads e.g. "rider (STILL UNACKNOWLEDGED after 5 min)") |

Some notifications are not on this list:

- **Voice OTP and SMS OTP** don't change.
- **Push and email** don't change.
- **Driver verification and document decisions** (`VERIFICATION_ITEM_*`, `DRIVER_APPROVED`) go by push and
  email only. Their events carry no phone number, so they can't go on WhatsApp yet. To add them, put the
  driver's phone on those events in `common`, then add a template the same way as the others.

## How the channel is chosen

For each message, notification-service checks these in order:

1. **`WHATSAPP_ENABLED` is false**: SMS only. The log reads `whatsapp.enabled=false - not sending ...`.
2. **The notification has no template id** (its env key is empty): SMS only. The log reads
   `No WhatsApp template configured for ...`. You can turn WhatsApp on for one notification at a time this way.
3. **The user turned WhatsApp off** (`whatsappOptIn=false`): SMS only. This applies only to the rows above
   that list a preference. If the preferences can't be read, the message goes by SMS.
4. Otherwise the service **tries WhatsApp**:
   - **Termii accepts it** (`code: "ok"`): no SMS is sent. A `WHATSAPP` row is written with
     `delivered=true` and Termii's `message_id`.
   - **Termii refuses it, or the call fails**: a `WHATSAPP` row is written with `delivered=false` and the
     reason in `error_code`. Reasons include an HTTP status such as `HTTP_400`, Termii's `code`, or a
     timeout exception name. **The same message then goes by SMS** and gets its own `SMS` row.

So a message is never sent on both channels when WhatsApp accepted it, and a WhatsApp failure never loses
the message. Each recipient is dispatched on its own, so one recipient's failure doesn't affect another's.
WhatsApp failures also don't affect push or email.

"Accepted" means Termii took the message, the same as for SMS today. It doesn't mean the message reached the
phone. If someone has no WhatsApp account, Termii may still accept the message without delivering it, and no
SMS fallback happens. Emergency contacts are the people most at risk here, so watch the delivery reports in
Termii's dashboard after launch. If they show many undelivered messages, set `WHATSAPP_TEMPLATE_SOS_CONTACT`
to empty to keep that alert on SMS.

The WhatsApp call has a 5 s connect timeout and a 10 s read timeout. In the worst case, an SOS that falls
back to SMS goes out about 15 s later than SMS alone would.

### Behaviour change for WhatsApp OTP

Before this change, an OTP resent with `channel=WHATSAPP` went as free text through Termii's
`/api/sms/send` with `channel=whatsapp`. That route is now gone. WhatsApp OTPs use the approved
authentication template. While WhatsApp is disabled or `WHATSAPP_TEMPLATE_OTP` is empty, those OTPs go
**by SMS**, so the user still gets a code.

## Provider API

The service uses Termii's template endpoint
([docs](https://developers.termii.com/templates)):

```
POST https://api.ng.termii.com/api/send/template
{
  "phone_number": "2348031234567",          // international, no "+" (converted from 0803...)
  "device_id":    "<WHATSAPP_TERMII_DEVICE_ID>",
  "template_id":  "<WHATSAPP_TEMPLATE_...>",
  "api_key":      "<WHATSAPP_TERMII_API_KEY or TERMII_API_KEY>",
  "data":         { "<placeholder>": "<value>", ... }
}
-> 200 {"code":"ok","message_id":"...","message":"Successfully Sent", ...}
```

Free text, sent through `/api/sms/send` with `channel=whatsapp`
([docs](https://developers.termii.com/messaging-api)), is not used. WhatsApp only delivers free text inside
the 24-hour window that opens after the recipient messages us. Emergency contacts and ops phones normally
haven't messaged us, so only an approved template can reach them.

Before a value is sent, line breaks and runs of spaces are collapsed and empty values become `-`, because
WhatsApp rejects template parameters containing them. Missing details are sent as `unknown` or
`not shared`, as in the SMS.

## Templates to create (placeholders must match exactly)

Each template's `data` keys are fixed in code (`WhatsAppMessageKind` and `NotificationEventListener`). The
template on Termii must use exactly these placeholder names. Suggested wording follows the SMS so that both
channels say the same thing. Category: **Utility**, except OTP, which is **Authentication**.

| Env key | Placeholders | Suggested body |
|---|---|---|
| `WHATSAPP_TEMPLATE_OTP` | `otp` | Authentication template. WhatsApp supplies the fixed text "{{otp}} is your verification code..." Termii allows only the `otp` key here. |
| `WHATSAPP_TEMPLATE_TRIP_SHARE` | `name`, `link` | Hi {{name}}, someone you know shared their CoRyd trip with you. Track it here: {{link}} |
| `WHATSAPP_TEMPLATE_SOS_CONTACT` | `name`, `location` | Hi {{name}}, this is an emergency alert from CoRyd. Someone you know triggered SOS during their trip. Last known location: {{location}} |
| `WHATSAPP_TEMPLATE_SOS_OPS` | `who`, `route`, `rider_phone`, `driver`, `driver_phone`, `car`, `location` | CoRyd SOS: the {{who}} pressed SOS on {{route}}. Rider {{rider_phone}}. Driver {{driver}} {{driver_phone}}, car {{car}}. Location: {{location}} |
| `WHATSAPP_TEMPLATE_SAFETY_ESCALATED` | `route`, `what`, `not_answered`, `answered_ok`, `car`, `location` | CoRyd safety: no reply to an Are-you-OK check. Trip {{route}}: {{what}}. No reply from: {{not_answered}}. Said OK: {{answered_ok}}. Car {{car}}. Last seen: {{location}} |

`name` is `there` when a contact has no saved name. `answered_ok` is `nobody` when nobody answered.
`location` is a Google Maps link, or `not shared`.

## Provider-side setup (product owner)

Do these steps once per Termii account. If dev and prod use separate accounts, do them on each.

1. **Turn on WhatsApp on the Termii account.** Ask Termii (account manager or support) to enable WhatsApp
   for the account the API key belongs to.
2. **Register a WhatsApp sender (device).** This needs a phone number that isn't already on WhatsApp or
   WhatsApp Business, plus the Meta Business verification Termii asks for: business name, documents, and the
   display name "CoRyd". When it's approved, copy the device id from **Manage Device** into
   `WHATSAPP_TERMII_DEVICE_ID`. It's different from the SMS sender id `TERMII_SENDER_ID`.
3. **Create the templates** in the table above, with the same placeholder names, and submit them for
   WhatsApp approval. Termii's docs say a template can't be used until WhatsApp approves it. The approved
   template's required keys are listed on Termii's **Device Subscription** page. Check that they match the
   table. Copy each template id into its env key.
4. **Fund the wallet.** WhatsApp sends are billed per conversation from the Termii balance. An empty balance
   shows up as `delivered=false` WhatsApp rows followed by SMS.
5. **Decide on ops.** If ops would rather get SOS and safety alerts by SMS, leave `WHATSAPP_TEMPLATE_SOS_OPS`
   and `WHATSAPP_TEMPLATE_SAFETY_ESCALATED` empty.

Then set `WHATSAPP_ENABLED=true` and restart notification-service.

## Env keys

| Key | Default | Meaning |
|---|---|---|
| `WHATSAPP_ENABLED` | `false` | Master switch. |
| `WHATSAPP_PROVIDER` | `termii` | Which provider implementation to use. Only `termii` exists. |
| `WHATSAPP_TERMII_DEVICE_ID` | empty | Termii WhatsApp device id. If empty, every send fails with `NO_DEVICE_ID` and goes by SMS. |
| `WHATSAPP_TERMII_API_KEY` | `TERMII_API_KEY` | Set only if WhatsApp uses a different Termii key from SMS. |
| `WHATSAPP_TERMII_BASE_URL` | `https://api.ng.termii.com` | Termii API host. |
| `WHATSAPP_TEMPLATE_OTP` / `_TRIP_SHARE` / `_SOS_CONTACT` / `_SOS_OPS` / `_SAFETY_ESCALATED` | empty | Template id per notification. If empty, that notification stays on SMS. |

`TERMII_ENABLED` still controls only SMS and voice. In dev with `TERMII_ENABLED=false` and
`WHATSAPP_ENABLED=false`, nothing is sent on either channel. Both are just logged.

## User preference

`notification.notification_preferences.whatsapp_opt_in` (Liquibase `008-notification-preferences-whatsapp.sql`)
defaults to `true`. Change it with:

```
PUT /notification/preferences/users/{userId}?whatsappOptIn=false
```

`promotionalOptIn` and `whatsappOptIn` are both optional on this endpoint. A flag you don't send keeps its
value. Turning WhatsApp off only changes the channel: the same message still goes by SMS. Trip-critical
messages can't be switched off (R-603/D-603).

## Swapping provider

To add another provider, implement `gateway.whatsapp.WhatsAppProvider`. Its `sendTemplate` method must
return a failed result and never throw. Register the class as a bean under its own `whatsapp.provider`
value, as `TermiiWhatsAppProvider` is registered for `termii`. Nothing else changes.

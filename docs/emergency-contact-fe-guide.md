# Emergency Contacts & SOS: FE Guide

Profile-service: base path `/profile`, port `9102`. Trip-service: base path `/trip`, port `9103`.

This answers the "Emergency contact — BE asks" request point by point.

**The key change:** emergency contacts now belong to the **person**, not to a role. A user who is both a rider and a driver has one set of contacts. Their rider profile and driver profile show the same primary contact, and editing it on either profile changes it for both.

---

## 1. Emergency contact on driver profiles: done

`GET /profile/drivers/me` and `PUT /profile/drivers/me` now include the same two fields as rider profiles:

```json
{
  "...": "...existing driver fields...",
  "emergencyContactName": "Funke Bakare",
  "emergencyContactPhone": "08051234567"
}
```

- On PUT, both fields are optional. Send both to set or change the contact.
- Sending only one of them returns `400`: `Add both a name and a phone number for your emergency contact`.
- Limits: name up to 100 characters, phone up to 20.
- The driver SOS sheet can be switched back on.

## 2. What SOS does with the contact

`POST /trip/bookings/{bookingId}/sos?latitude=&longitude=`

1. SOS works for **both riders and drivers**. Whoever presses it, their own emergency contacts are alerted.
2. Each of that person's contacts gets an **SMS** that includes the last known location, if the app sent `latitude`/`longitude`. Every saved contact is alerted, not only the primary. There's no phone call.
3. The person who pressed SOS gets a confirmation push (type `SOS_TRIGGERED`). It only says a contact was alerted when one actually was:
   - With contacts: **"Emergency alert sent"**, "Your emergency contact has been notified by SMS."
   - With none: **"No emergency contact set"**, "We couldn't alert anyone - add an emergency contact in your profile."

Two bugs are fixed here:

- **Driver SOS.** When a driver pressed SOS, it used to text the **rider's** contacts and tell the driver theirs had been notified.
- **Rider contacts saved on the profile.** A rider who set a contact through the profile form had it ignored by SOS. Existing contacts saved that way are copied over when this release deploys.

**`triggeredBy` is no longer needed.** The backend uses the logged-in user. Older apps can keep sending it; it's ignored.

**Please also show a warning on the SOS sheet** when the user has no contact (`emergencyContactPhone` is `null` on their profile), so they aren't surprised by the "No emergency contact set" push.

## 3. How `PUT /riders/me` and `PUT /drivers/me` behave: fields left out are now kept

Both used to **replace** the profile, so an older app version that left out a field wiped it. Now, **only the fields you send are updated**:

| Field left out of the request | Before | Now |
|---|---|---|
| `photoUrl`, `email`, rider `lastName` | wiped | kept |
| `emergencyContactName` + `emergencyContactPhone` | wiped | kept |

- The required fields haven't changed. Riders: `firstName`. Drivers: `firstName`, `lastName`, `dateOfBirth`, `idNumber`.
- **One trade-off:** leaving a field out can't be used to clear it any more. Nothing in the app clears these today. If removing a contact is needed later, it'll get its own endpoint.
- Sending the full profile, as the current app does, works exactly as before.

## 4. Multiple contacts: already supported

The profile fields show the **primary** contact. A list with one primary already exists, and it now works for drivers too, because contacts belong to the person:

```
GET  /profile/riders/me/emergency-contacts                      → the user's contacts
POST /profile/riders/me/emergency-contacts                      → add { "name", "phone" } (the first one added becomes primary)
PUT  /profile/riders/me/emergency-contacts/{contactId}/select   → make that contact primary
```

The paths say `riders`, but they act on the logged-in user, whatever role they're in. One contact is enough for launch. If you add a multi-contact screen later, these endpoints are ready, and SOS already texts every contact in the list.

---

## Checklist

- [ ] Driver profile screen: read/write `emergencyContactName` + `emergencyContactPhone`; re-enable the driver SOS screen
- [ ] Send both contact fields together; show the `400` message if one is missing
- [ ] SOS: stop relying on `triggeredBy` (optional, ignored); show a "no emergency contact" warning when the profile has none
- [ ] Profile PUTs: no change needed. Older versions no longer wipe fields.

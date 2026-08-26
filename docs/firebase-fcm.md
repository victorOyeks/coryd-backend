# Firebase FCM — Push Notification Setup

## Overview

CoRyd uses Firebase Cloud Messaging (FCM) to deliver push notifications to
rider and driver devices. `notification-service` owns all FCM dispatch -
same architecture as marketrun-ng's, ported over service-for-service.

---

## Architecture

```
Kafka Event
    |
    v
NotificationEventListener  ->  NotificationDispatchService.sendPush()
                                        |
                                saves UserNotification (in-app feed, always)
                                        |
                                DeviceTokenRepository.findByUserId()
                                (every token on file for that user)
                                        |
                                FcmService.sendPush() -> FirebaseMessaging.send()
                                        |
                                Rider/Driver device
```

Unlike marketrun-ng (Docker), CoRyd's services run as systemd units directly
on the VPS - so the credentials file is a plain file on disk at an absolute
path, not a Docker volume mount.

---

## Server-Side Setup

### 1. Firebase Project

- Go to the [Firebase Console](https://console.firebase.google.com)
- Create a project (or reuse the same one marketrun-ng uses, if this app
  should share a Firebase project - check with the team first)
- **Project Settings -> Service Accounts -> Generate new private key**
- Download the JSON file

### 2. VPS Credentials Placement

```bash
# From your local machine
scp firebase-service-account.json devoyeks@<vps-ip>:/opt/coryd-dev/notification-service/firebase-service-account.json

# On the VPS
chmod 600 /opt/coryd-dev/notification-service/firebase-service-account.json
```

### 3. Environment Variable

In `/opt/coryd-dev/.env.dev` (see `.env.dev.example`):
```
FIREBASE_CREDENTIALS_PATH=/opt/coryd-dev/notification-service/firebase-service-account.json
```

Must be an **absolute path** - `coryd-notification-service.service` has no
`WorkingDirectory=` set, so a relative path resolves against systemd's
default working directory, not the app's own folder.

```bash
sudo systemctl restart coryd-notification-service
```

---

## Code Components

### FirebaseConfig

Initialises the Firebase app on service startup. Skips gracefully if the
credentials file is missing (push silently disabled - DB notifications still
save).

```
notification-service/.../config/FirebaseConfig.java
```

- `jakarta.annotation.PostConstruct` (Spring Boot 3, not `javax`)
- Only initialises once (`FirebaseApp.getApps().isEmpty()` guard)
- Logs an error and continues on `IOException` (file not found/unreadable)

### FcmService

Sends to a single FCM token, called once per device token on file for the
recipient user.

```
notification-service/.../service/FcmService.java
```

**FCM payload structure:**
```json
{
  "notification": {
    "title": "Action needed on your verification",
    "body": "One of your verification items needs attention: Not clear enough"
  },
  "data": {
    "type": "DRIVER_REJECTED",
    "referenceId": null
  }
}
```

- `type` - always present (the Kafka event type), used by the FE to route on tap
- `referenceId` - a booking id when the event relates to one, `null` otherwise
- Stale/unregistered tokens are auto-deleted (`UNREGISTERED` error code
  triggers `DeviceTokenRepository.deleteByUserIdAndFcmToken`)

### DeviceToken entity + registration

```
notification-service/.../entity/DeviceToken.java
notification-service/.../service/DeviceTokenService.java
notification-service/.../controller/DeviceTokenController.java
```

`fcmToken` is globally unique. `DeviceTokenService.register()` upserts by
token, not by (userId, token) - re-registering the same token (app reopened,
token unchanged) updates the existing row instead of failing on the unique
constraint, and a different account logging into the same physical device
correctly re-points that token to the new user.

---

## Client (FE) Integration

### Step 1 - Register token after login

```
POST /notification/device-tokens/users/{userId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "fcmToken": "<fcm_device_token>"
}
```

### Step 2 - Remove token on logout

```
DELETE /notification/device-tokens/users/{userId}?fcmToken=<fcm_device_token>
Authorization: Bearer <jwt>
```

### Step 3 - Handle incoming push (FE)

The FE uses `type` (+ `referenceId` when present) from the data payload to
navigate on tap. Current event types wired to push: `BOOKING_CONFIRMED`,
`BOOKING_CANCELLED`, `DRIVER_EN_ROUTE`, `DRIVER_ARRIVED`, `ROUTE_CANCELLED`,
`TRIP_COMPLETED`, `NOTIFY_ME_MATCHED`, `DOCUMENT_EXPIRING`, `DRIVER_APPROVED`,
`DRIVER_REJECTED`, `SOS_TRIGGERED` - see `NotificationEventListener.java`
for the full, current list.

---

## Behaviour When Firebase Is Not Configured

If `firebase-service-account.json` is absent or unreadable at the configured
path:
- `FirebaseConfig` logs an error and skips initialisation
- `FcmService.sendPush()` detects `FirebaseApp.getApps().isEmpty()` and
  returns `false` (logged as a failed `NotificationLog` row) without calling
  Firebase
- The Kafka-driven notification is **still saved to the DB**
  (`UserNotification`) - users still see it in-app
- Only the device pop-up is skipped

This means the service starts cleanly in local development without Firebase
credentials on disk.

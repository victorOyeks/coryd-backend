package org.coryd.notification.gateway;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

    private final DeviceTokenRepository deviceTokenRepository;

    /** What Firebase said: its message id when it took the push, its error code when it didn't. */
    public record PushResult(boolean delivered, String messageId, String errorCode) {
        static PushResult failed(String errorCode) {
            return new PushResult(false, null, errorCode);
        }
    }

    public PushResult sendPush(String userId, String fcmToken, String title, String body, String eventType, String referenceId,
                            String routePostId) {
        java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
        if (referenceId != null) {
            data.put("referenceId", referenceId);
        }
        if (routePostId != null) {
            data.put("routePostId", routePostId);
        }
        return sendPush(userId, fcmToken, title, body, eventType, data);
    }

    /** A push whose data carries these fields next to "type" (eventType) - e.g. an onboarding reminder's step and role. */
    public PushResult sendPush(String userId, String fcmToken, String title, String body, String eventType,
                               java.util.Map<String, String> data) {
        return sendGroupedPush(userId, fcmToken, title, body, eventType, data, null);
    }

    /**
     * groupKey, when set, keeps a busy conversation to one entry in the tray: it is the Android notification tag (a newer
     * push with the same tag replaces the older one) and the iOS thread-id (pushes with the same thread stack together).
     * Null leaves both unset, as for every other push.
     */
    public PushResult sendGroupedPush(String userId, String fcmToken, String title, String body, String eventType,
                                      java.util.Map<String, String> data, String groupKey) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("Firebase not initialized - skipping push for userId={}", userId);
            return PushResult.failed("FIREBASE_NOT_INITIALIZED");
        }

        try {
            String response = FirebaseMessaging.getInstance().send(message(fcmToken, title, body, eventType, data, groupKey));
            log.info("FCM push sent to userId={} - messageId={}", userId, response);
            return new PushResult(true, response, null);
        } catch (FirebaseMessagingException e) {
            log.error("FCM send failed for userId={} token={} - code={} message={}",
                    userId, fcmToken, e.getMessagingErrorCode(), e.getMessage());
            // Stale token - remove it so we don't keep trying
            if ("UNREGISTERED".equals(String.valueOf(e.getMessagingErrorCode()))) {
                deviceTokenRepository.deleteByUserIdAndFcmToken(userId, fcmToken);
                log.info("Removed stale FCM token for userId={}", userId);
            }
            return PushResult.failed(String.valueOf(e.getMessagingErrorCode()));
        } catch (Exception e) {
            // Anything the Admin SDK throws that ISN'T FirebaseMessagingException (a malformed
            // service-account credential surfaces here as IllegalArgumentException/IllegalStateException,
            // not the typed exception above) would otherwise escape this method uncaught - since the
            // caller (NotificationDispatchService.sendPush) is @Async, that means Spring's default
            // async-exception handler swallows it generically, with none of this method's userId/token
            // context attached. Catching it here guarantees every failure on this path is logged.
            log.error("FCM send threw an unexpected exception for userId={} token={}: {}", userId, fcmToken, e.getMessage(), e);
            return PushResult.failed(e.getClass().getSimpleName());
        }
    }

    /** The FCM message itself - visible to tests, which check what each kind of push carries. */
    static Message message(String fcmToken, String title, String body, String eventType,
                           java.util.Map<String, String> data, String groupKey) {
        Message.Builder builder = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                // "default" is the channel id the app itself creates - explicit here rather
                // than left for the client SDK to guess, since an unrecognised channel id
                // makes Android drop the notification with no error on either side.
                .setAndroidConfig(AndroidConfig.builder()
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("default")
                                .setTag(groupKey)
                                .build())
                        .build())
                // Android takes its sound from the channel; iOS plays nothing at all unless
                // aps.sound is set, so the alert would appear silently on every device.
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setSound("default")
                                .setThreadId(groupKey)
                                .build())
                        .build())
                .putData("type", eventType);

        if (data != null) {
            data.forEach((key, value) -> {
                if (key != null && value != null && !"type".equals(key)) {
                    builder.putData(key, value);
                }
            });
        }
        return builder.build();
    }
}

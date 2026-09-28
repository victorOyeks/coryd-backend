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

    public boolean sendPush(String userId, String fcmToken, String title, String body, String eventType, String referenceId) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("Firebase not initialized - skipping push for userId={}", userId);
            return false;
        }

        try {
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
                                    .build())
                            .build())
                    // Android takes its sound from the channel; iOS plays nothing at all unless
                    // aps.sound is set, so the alert would appear silently on every device.
                    .setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder()
                                    .setSound("default")
                                    .build())
                            .build())
                    .putData("type", eventType);

            if (referenceId != null) {
                builder.putData("referenceId", referenceId);
            }

            String response = FirebaseMessaging.getInstance().send(builder.build());
            log.info("FCM push sent to userId={} - messageId={}", userId, response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("FCM send failed for userId={} token={} - code={} message={}",
                    userId, fcmToken, e.getMessagingErrorCode(), e.getMessage());
            // Stale token - remove it so we don't keep trying
            if ("UNREGISTERED".equals(String.valueOf(e.getMessagingErrorCode()))) {
                deviceTokenRepository.deleteByUserIdAndFcmToken(userId, fcmToken);
                log.info("Removed stale FCM token for userId={}", userId);
            }
            return false;
        } catch (Exception e) {
            // Anything the Admin SDK throws that ISN'T FirebaseMessagingException (a malformed
            // service-account credential surfaces here as IllegalArgumentException/IllegalStateException,
            // not the typed exception above) would otherwise escape this method uncaught - since the
            // caller (NotificationDispatchService.sendPush) is @Async, that means Spring's default
            // async-exception handler swallows it generically, with none of this method's userId/token
            // context attached. Catching it here guarantees every failure on this path is logged.
            log.error("FCM send threw an unexpected exception for userId={} token={}: {}", userId, fcmToken, e.getMessage(), e);
            return false;
        }
    }
}

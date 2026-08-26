package org.coryd.notification.service;

import com.google.firebase.FirebaseApp;
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
        }
    }
}

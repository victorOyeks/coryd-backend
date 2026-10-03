package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OtpChannel;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final TermiiSmsService termiiSmsService;
    private final FcmService fcmService;
    private final EmailService emailService;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final UserNotificationRepository userNotificationRepository;

    @Async("notificationExecutor")
    @Transactional
    public void sendSms(String phone, String message, String eventType) {
        boolean delivered = termiiSmsService.send(phone, message);
        notificationLogRepository.save(NotificationLog.builder()
                .recipientPhone(phone)
                .channel(NotificationChannel.SMS)
                .eventType(eventType)
                .delivered(delivered)
                .build());
    }

    /** code is the bare OTP digits, needed separately from message since the voice channel reads
     * out its own script around the code rather than speaking a pre-built SMS-style sentence. */
    @Async("notificationExecutor")
    @Transactional
    public void sendOtp(String phone, String message, String code, String eventType, OtpChannel channel) {
        boolean delivered = switch (channel) {
            case WHATSAPP -> termiiSmsService.sendWhatsapp(phone, message);
            case VOICE -> termiiSmsService.sendVoiceOtp(phone, code);
            case SMS -> termiiSmsService.send(phone, message);
        };
        NotificationChannel loggedChannel = switch (channel) {
            case WHATSAPP -> NotificationChannel.WHATSAPP;
            case VOICE -> NotificationChannel.VOICE;
            case SMS -> NotificationChannel.SMS;
        };
        notificationLogRepository.save(NotificationLog.builder()
                .recipientPhone(phone)
                .channel(loggedChannel)
                .eventType(eventType)
                .delivered(delivered)
                .build());
    }

    @Async("notificationExecutor")
    @Transactional
    public void sendPush(String userId, String title, String body, String eventType, String bookingId) {
        push(userId, title, body, eventType, bookingId, null);
    }

    /** A notification about a route rather than one booking - the feed and the push carry routePostId. */
    @Async("notificationExecutor")
    @Transactional
    public void sendRoutePush(String userId, String title, String body, String eventType, String routePostId) {
        push(userId, title, body, eventType, null, routePostId);
    }

    private void push(String userId, String title, String body, String eventType, String bookingId, String routePostId) {
        // One feed entry per logical notification, not one per device - a rider with two
        // phones should see this once in their bell, even though it's pushed to both.
        userNotificationRepository.save(UserNotification.builder()
                .userId(userId)
                .title(title)
                .body(body)
                .eventType(eventType)
                .bookingId(bookingId)
                .routePostId(routePostId)
                .build());

        for (DeviceToken token : deviceTokenRepository.findByUserId(userId)) {
            boolean delivered = fcmService.sendPush(userId, token.getFcmToken(), title, body, eventType, bookingId, routePostId);
            notificationLogRepository.save(NotificationLog.builder()
                    .channel(NotificationChannel.PUSH)
                    .eventType(eventType)
                    .delivered(delivered)
                    .build());
        }
    }

    /** Caller decides whether an email is worth sending (e.g. only if the recipient has one on file) - this method always attempts it. */
    @Async("notificationExecutor")
    @Transactional
    public void sendEmail(String toEmail, String subject, String body, String eventType) {
        boolean delivered = emailService.send(toEmail, subject, body);
        notificationLogRepository.save(NotificationLog.builder()
                .recipientEmail(toEmail)
                .channel(NotificationChannel.EMAIL)
                .eventType(eventType)
                .delivered(delivered)
                .build());
    }
}

package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OtpChannel;
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

    @Async
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

    @Async
    @Transactional
    public void sendOtp(String phone, String message, String eventType, OtpChannel channel) {
        boolean isWhatsapp = channel == OtpChannel.WHATSAPP;
        boolean delivered = isWhatsapp
                ? termiiSmsService.sendWhatsapp(phone, message)
                : termiiSmsService.send(phone, message);
        notificationLogRepository.save(NotificationLog.builder()
                .recipientPhone(phone)
                .channel(isWhatsapp ? NotificationChannel.WHATSAPP : NotificationChannel.SMS)
                .eventType(eventType)
                .delivered(delivered)
                .build());
    }

    @Async
    @Transactional
    public void sendPush(String userId, String title, String body, String eventType, String bookingId) {
        // One feed entry per logical notification, not one per device - a rider with two
        // phones should see this once in their bell, even though it's pushed to both.
        userNotificationRepository.save(UserNotification.builder()
                .userId(userId)
                .title(title)
                .body(body)
                .eventType(eventType)
                .bookingId(bookingId)
                .build());

        for (DeviceToken token : deviceTokenRepository.findByUserId(userId)) {
            boolean delivered = fcmService.sendPush(token.getFcmToken(), title, body);
            notificationLogRepository.save(NotificationLog.builder()
                    .channel(NotificationChannel.PUSH)
                    .eventType(eventType)
                    .delivered(delivered)
                    .build());
        }
    }

    /** Caller decides whether an email is worth sending (e.g. only if the recipient has one on file) - this method always attempts it. */
    @Async
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

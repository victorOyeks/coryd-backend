package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final TermiiSmsService termiiSmsService;
    private final FcmService fcmService;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationLogRepository notificationLogRepository;

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
    public void sendPush(String userId, String title, String body, String eventType) {
        for (DeviceToken token : deviceTokenRepository.findByUserId(userId)) {
            boolean delivered = fcmService.sendPush(token.getFcmToken(), title, body);
            notificationLogRepository.save(NotificationLog.builder()
                    .channel(NotificationChannel.PUSH)
                    .eventType(eventType)
                    .delivered(delivered)
                    .build());
        }
    }
}

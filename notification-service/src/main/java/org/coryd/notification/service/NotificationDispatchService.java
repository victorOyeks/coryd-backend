package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OtpChannel;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessage;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.coryd.notification.gateway.whatsapp.WhatsAppSendResult;
import org.coryd.notification.gateway.whatsapp.WhatsAppService;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final TermiiSmsService termiiSmsService;
    private final WhatsAppService whatsAppService;
    private final NotificationPreferencesRepository preferencesRepository;
    private final FcmService fcmService;
    private final EmailService emailService;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final UserNotificationRepository userNotificationRepository;

    @Async("notificationExecutor")
    @Transactional
    public void sendSms(String phone, String message, String eventType) {
        sendSmsNow(phone, message, eventType);
    }

    /**
     * WhatsApp first, SMS only if WhatsApp didn't take it - never both. WhatsApp is skipped (straight
     * to SMS, no outbound WhatsApp call) when WHATSAPP_ENABLED is off, the notification has no
     * template, or preferenceUserId has turned WhatsApp off. A refused or failed WhatsApp send is
     * logged as an undelivered WHATSAPP row and the same message then goes by SMS.
     *
     * @param preferenceUserId whose notification preferences govern the channel - the user the message
     *                         is sent for (e.g. the rider whose emergency contacts are alerted); null when
     *                         no user's preferences apply (ops alerts)
     */
    @Async("notificationExecutor")
    @Transactional
    public void sendWhatsappOrSms(String phone, String smsMessage, WhatsAppMessage whatsappMessage,
                                  String eventType, String preferenceUserId) {
        if (whatsappAllowedBy(preferenceUserId) && tryWhatsapp(phone, whatsappMessage, eventType)) {
            return;
        }
        sendSmsNow(phone, smsMessage, eventType);
    }

    /** code is the bare OTP digits, needed separately from message since the voice channel reads
     * out its own script around the code rather than speaking a pre-built SMS-style sentence.
     * WHATSAPP falls back to SMS when WhatsApp is off or fails - the user still gets a code. */
    @Async("notificationExecutor")
    @Transactional
    public void sendOtp(String phone, String message, String code, String eventType, OtpChannel channel) {
        switch (channel) {
            case WHATSAPP -> {
                WhatsAppMessage otp = new WhatsAppMessage(WhatsAppMessageKind.OTP, Map.of("otp", code));
                if (!tryWhatsapp(phone, otp, eventType)) {
                    sendSmsNow(phone, message, eventType);
                }
            }
            case VOICE -> notificationLogRepository.save(NotificationLog.builder()
                    .recipientPhone(phone)
                    .channel(NotificationChannel.VOICE)
                    .eventType(eventType)
                    .delivered(termiiSmsService.sendVoiceOtp(phone, code))
                    .build());
            case SMS -> sendSmsNow(phone, message, eventType);
        }
    }

    /** True only when WhatsApp took the message. False (nothing sent) when WhatsApp isn't attempted. */
    private boolean tryWhatsapp(String phone, WhatsAppMessage message, String eventType) {
        Optional<WhatsAppSendResult> outcome;
        try {
            outcome = whatsAppService.send(phone, message);
        } catch (Exception e) {
            log.error("WhatsApp send for {} to {} failed unexpectedly - falling back to SMS: {}", eventType, phone, e.getMessage());
            outcome = Optional.of(WhatsAppSendResult.failed(e.getClass().getSimpleName()));
        }
        if (outcome.isEmpty()) {
            return false;
        }
        WhatsAppSendResult result = outcome.get();
        notificationLogRepository.save(NotificationLog.builder()
                .recipientPhone(phone)
                .channel(NotificationChannel.WHATSAPP)
                .eventType(eventType)
                .delivered(result.delivered())
                .providerMessageId(result.providerMessageId())
                .errorCode(result.errorCode())
                .build());
        if (!result.delivered()) {
            log.warn("WhatsApp {} to {} not accepted ({}) - sending by SMS instead", eventType, phone, result.errorCode());
        }
        return result.delivered();
    }

    private boolean whatsappAllowedBy(String userId) {
        if (userId == null) {
            return true;
        }
        try {
            return preferencesRepository.findByUserId(userId)
                    .map(NotificationPreferences::isWhatsappOptIn)
                    .orElse(true);
        } catch (Exception e) {
            // A preferences lookup that fails must not cost the message - SMS is always allowed.
            log.error("Couldn't read notification preferences for {} - using SMS: {}", userId, e.getMessage());
            return false;
        }
    }

    private void sendSmsNow(String phone, String message, String eventType) {
        boolean delivered = termiiSmsService.send(phone, message);
        notificationLogRepository.save(NotificationLog.builder()
                .recipientPhone(phone)
                .channel(NotificationChannel.SMS)
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
            FcmService.PushResult result = fcmService.sendPush(userId, token.getFcmToken(), title, body, eventType, bookingId, routePostId);
            notificationLogRepository.save(NotificationLog.builder()
                    .userId(userId)
                    .channel(NotificationChannel.PUSH)
                    .eventType(eventType)
                    .delivered(result.delivered())
                    .providerMessageId(result.messageId())
                    .errorCode(result.errorCode())
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

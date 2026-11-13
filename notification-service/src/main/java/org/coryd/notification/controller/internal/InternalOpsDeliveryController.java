package org.coryd.notification.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.repository.NotificationLogRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Service-to-service only (ops-service) - gated by InternalApiKeyFilter via /internal/**. Which channel each SOS text or
 * ops alert actually went out on (WhatsApp, or SMS after WhatsApp refused) and whether the provider took it. Matched to an
 * incident by event type, time window and phone, since delivery rows carry no incident id.
 */
@Hidden
@RestController
@RequestMapping("/internal/ops/deliveries")
@RequiredArgsConstructor
public class InternalOpsDeliveryController {

    static final Duration MAX_WINDOW = Duration.ofHours(6);
    static final int MAX_ROWS = 500;

    private final NotificationLogRepository notificationLogRepository;

    public record Delivery(Instant at, String eventType, String channel, String recipientPhone, String recipientEmail, String userId,
                           boolean delivered, String errorCode) {
    }

    @GetMapping
    public ApiResponse<List<Delivery>> deliveries(@RequestParam List<String> eventTypes,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new BadRequestException("from/to: a window of up to 6 hours");
        }
        return ApiResponse.ok(notificationLogRepository.findByEventTypeInAndCreatedAtBetweenOrderByCreatedAtAsc(eventTypes, from, to).stream()
                .limit(MAX_ROWS)
                .map(InternalOpsDeliveryController::delivery)
                .toList());
    }

    private static Delivery delivery(NotificationLog l) {
        return new Delivery(l.getCreatedAt(), l.getEventType(), l.getChannel() == null ? null : l.getChannel().name(), l.getRecipientPhone(),
                l.getRecipientEmail(), l.getUserId(), l.isDelivered(), l.getErrorCode());
    }
}

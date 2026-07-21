package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.trip.config.KafkaConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final BookingService bookingService;

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCEEDED, groupId = KafkaConfig.GROUP_ID)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        bookingService.confirm(event.getBookingId());
        log.info("Booking {} confirmed after successful payment", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = KafkaConfig.GROUP_ID)
    public void onPaymentFailed(PaymentFailedEvent event) {
        bookingService.releaseOnPaymentFailure(event.getBookingId());
        log.info("Booking {} released after failed payment: {}", event.getBookingId(), event.getReason());
    }
}

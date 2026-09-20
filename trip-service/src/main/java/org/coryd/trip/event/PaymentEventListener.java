package org.coryd.trip.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.service.BookingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final BookingService bookingService;

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCEEDED, groupId = KafkaConfig.GROUP_ID)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        // confirm() itself logs a warning (and refunds instead) if the booking's seat hold had
        // already expired by the time this arrived - don't unconditionally claim success here too.
        bookingService.confirm(event.getBookingId());
        log.info("Payment succeeded for booking {} - handed to BookingService.confirm()", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = KafkaConfig.GROUP_ID)
    public void onPaymentFailed(PaymentFailedEvent event) {
        bookingService.releaseOnPaymentFailure(event.getBookingId());
        log.info("Booking {} released after failed payment: {}", event.getBookingId(), event.getReason());
    }
}

package org.coryd.payment.event;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.payment.config.KafkaConfig;
import org.coryd.payment.service.EarningsService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final EarningsService earningsService;

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = KafkaConfig.GROUP_ID)
    public void onBookingCancelled(BookingCancelledEvent event) {
        // R-704/D-703: driver- or rider-initiated cancellation triggers a full automatic refund.
        earningsService.refund(event.getBookingId(), "Booking cancelled by " + event.getCancelledBy());
    }
}

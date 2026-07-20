package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final EarningsService earningsService;

    @KafkaListener(topics = "coryd-booking-cancelled", groupId = "coryd-payment-service")
    public void onBookingCancelled(BookingCancelledEvent event) {
        // R-704/D-703: driver- or rider-initiated cancellation triggers a full automatic refund.
        earningsService.refund(event.getBookingId(), "Booking cancelled by " + event.getCancelledBy());
    }
}

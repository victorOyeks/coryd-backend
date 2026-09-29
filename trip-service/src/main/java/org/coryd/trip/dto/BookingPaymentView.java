package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingPaymentView(
        String bookingId,
        String riderId,
        String driverId,
        BookingStatus status,
        BigDecimal fareTotal,
        Instant holdExpiresAt
) {
}

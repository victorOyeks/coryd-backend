package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

/** One passenger row on the Trip Receipt - starsGiven is null if the driver hasn't rated them yet. */
public record ReceiptPassenger(
        String riderId,
        String riderName,
        String riderPhotoUrl,
        BookingStatus status,
        Integer starsGiven
) {
}

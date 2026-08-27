package org.coryd.payment.dev;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Booking ids whose payment-side rows trip-service wants gone, sent by its route-reset dev tool. */
public record PurgeBookingsRequest(@NotEmpty List<String> bookingIds) {
}

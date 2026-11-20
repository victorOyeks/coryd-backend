package org.coryd.payment.dto;

import jakarta.validation.constraints.NotBlank;
import org.coryd.common.enums.PaymentMethod;

import java.math.BigDecimal;

/** riderId, driverId and amount are ignored - they come from the booking. */
public record InitiatePaymentRequest(
        // NotBlank, not NotNull: an empty id reached trip-service as /internal/bookings//payment-view and came back a 500.
        @NotBlank(message = "is required - the booking you're paying for") String bookingId,
        String riderId,
        String driverId,
        BigDecimal amount,
        String riderEmail,
        PaymentMethod paymentMethod,
        String callbackUrl,
        String savedCardId
) {
}

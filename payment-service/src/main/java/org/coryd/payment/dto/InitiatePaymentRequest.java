package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record InitiatePaymentRequest(
        @NotNull String bookingId,
        @NotNull String riderId,
        @NotNull String driverId,
        @NotNull @Positive BigDecimal amount,
        String riderEmail
) {
}

package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.coryd.common.enums.PaymentMethod;

import java.math.BigDecimal;

public record InitiatePaymentRequest(
        @NotNull String bookingId,
        @NotNull String riderId,
        @NotNull String driverId,
        @NotNull @Positive BigDecimal amount,
        String riderEmail,
        /** Null defaults to PAYSTACK - keeps existing callers working unchanged. */
        PaymentMethod paymentMethod
) {
}

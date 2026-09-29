package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.PaymentMethod;

import java.math.BigDecimal;

public record InitiatePaymentRequest(
        @NotNull String bookingId,
        String riderId,
        String driverId,
        BigDecimal amount,
        String riderEmail,
        PaymentMethod paymentMethod,
        String callbackUrl
) {
}

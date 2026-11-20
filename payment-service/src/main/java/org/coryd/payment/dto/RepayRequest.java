package org.coryd.payment.dto;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** amount is optional - leave it out to pay everything owed. */
public record RepayRequest(
        @Positive(message = "must be more than zero - or leave it out to pay everything owed") BigDecimal amount,
        String email,
        String callbackUrl
) {
}

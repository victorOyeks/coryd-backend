package org.coryd.payment.dto;

import java.math.BigDecimal;

/** amount is optional - leave it out to pay everything owed. */
public record RepayRequest(
        BigDecimal amount,
        String email,
        String callbackUrl
) {
}

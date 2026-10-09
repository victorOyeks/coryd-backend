package org.coryd.payment.dto;

import java.math.BigDecimal;

/** minimumToUnblock is what the driver must pay to post routes again - 0 when they aren't blocked. */
public record OwedBalanceResponse(
        BigDecimal amountOwed,
        BigDecimal owedLimit,
        boolean blocked,
        BigDecimal minimumToUnblock
) {
}

package org.coryd.payment.dto;

import java.math.BigDecimal;

public record DriverPostingStatus(
        boolean hasPayoutAccount,
        BigDecimal amountOwed,
        BigDecimal owedLimit,
        boolean overOwedLimit
) {
}

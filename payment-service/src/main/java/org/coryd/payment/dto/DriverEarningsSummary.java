package org.coryd.payment.dto;

import java.math.BigDecimal;

public record DriverEarningsSummary(
        BigDecimal lifetimeGross,
        BigDecimal lifetimeNet,
        BigDecimal todayNet,
        BigDecimal availableBalance,
        BigDecimal settlementDebt
) {
}

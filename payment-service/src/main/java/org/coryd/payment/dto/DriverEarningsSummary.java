package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** paidToBank and payoutInProgress are Paystack's settlements to the driver's bank, as last synced. */
public record DriverEarningsSummary(
        BigDecimal lifetimeGross,
        BigDecimal lifetimeNet,
        BigDecimal todayNet,
        BigDecimal earnedSinceLastPayout,
        BigDecimal paidToBank,
        BigDecimal payoutInProgress,
        Instant lastPayoutAt,
        BigDecimal settlementDebt
) {
}

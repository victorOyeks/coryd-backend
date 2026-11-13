package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A driver's money in one place. lifetime and period: what trips earned (gross), CoRyd's commission, and the driver's net.
 * owed: settlement debt now - what the driver owes CoRyd from refunded trips and ops adjustments. payouts: Paystack
 * settlements to their bank (all time). CoRyd holds no driver money, so there is no "owed to the driver" balance.
 */
public record DriverStatementView(String driverId, Totals lifetime, Period period, Owed owed, Payouts payouts,
                                  BigDecimal commissionRatePercent) {

    public record Totals(BigDecimal gross, BigDecimal commission, BigDecimal net) {
    }

    public record Period(LocalDate from, LocalDate to, BigDecimal gross, BigDecimal commission, BigDecimal net,
                         BigDecimal refundsChargedToDriver, BigDecimal opsAdjustments) {
    }

    public record Owed(BigDecimal amount, BigDecimal limit, boolean blockedFromPosting, BigDecimal minimumToUnblock) {
    }

    public record Payouts(BigDecimal paid, BigDecimal inProgress, BigDecimal failed, Instant lastPaidAt) {
    }
}

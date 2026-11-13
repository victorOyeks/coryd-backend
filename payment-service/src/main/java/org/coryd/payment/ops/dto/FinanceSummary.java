package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The finance dashboard for [from, to] (Lagos days, inclusive). Flows are within the window; "now" figures are point in time.
 * grossBookings: booking payments that succeeded. refunds: refunds of booking payments that went through (duplicate charges
 * apart - they were never in grossBookings). netCollected = grossBookings - refunds. commission: CoRyd's share of trips
 * credited to drivers in the window. payouts: Paystack settlements to drivers' banks.
 */
public record FinanceSummary(LocalDate from, LocalDate to, String timezone, Money grossBookings, Money refunds,
                             Money duplicateChargesRefunded, BigDecimal netCollected, Commission commission, Money debtRepaid,
                             Payouts payouts, Debt driverDebt, Failures failures, List<Day> daily) {

    public record Money(long count, BigDecimal amount) {
    }

    public record Commission(BigDecimal tripGross, BigDecimal commission, BigDecimal driverNet) {
    }

    public record Payouts(Money paid, Money failed, Money pendingNow) {
    }

    public record Debt(BigDecimal outstandingNow, long driversOwingNow) {
    }

    public record Failures(long failedPayments, long failedPayouts, Money refundsAwaitingRetry, long failedManualRefunds,
                           long approvalsPending) {
    }

    public record Day(LocalDate date, BigDecimal grossBookings, long payments, BigDecimal refunds, BigDecimal commission,
                      BigDecimal payouts) {
    }
}

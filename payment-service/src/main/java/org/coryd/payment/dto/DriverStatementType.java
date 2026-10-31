package org.coryd.payment.dto;

/** What a driver statement row is. The owed-money types match DebtEntryType. */
public enum DriverStatementType {
    /** A completed trip, or a kept late-cancellation fee - amount is net of commission. */
    TRIP_EARNING,
    /** Paystack's payout to the driver's bank - the next working day after the fare. */
    PAYOUT,
    REFUNDED_TRIP,
    RECOVERED_FROM_FARE,
    PAID_BY_CARD,
    OPS_ADJUSTMENT,
    /** A card payment of what they owed went over - the extra went back to their card. */
    OVERPAYMENT_REFUNDED
}

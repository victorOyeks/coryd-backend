package org.coryd.common.enums;

public enum PaymentPurpose {
    BOOKING_PAYMENT,
    /** A driver paying back what they owe from refunded trips, so they can post routes again. */
    DEBT_REPAYMENT
}

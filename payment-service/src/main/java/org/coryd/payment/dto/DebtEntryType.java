package org.coryd.payment.dto;

public enum DebtEntryType {
    /** A trip the driver was paid for was refunded - their share is owed back. */
    REFUNDED_TRIP,
    /** Taken from a fare the driver earned. */
    RECOVERED_FROM_FARE,
    /** The driver paid it back by card. */
    PAID_BY_CARD,
    /** A correction by ops. */
    OPS_ADJUSTMENT
}

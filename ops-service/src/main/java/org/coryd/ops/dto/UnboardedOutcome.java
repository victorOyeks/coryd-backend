package org.coryd.ops.dto;

/** Marking a stuck trip completed: what happens to riders who were never picked up. */
public enum UnboardedOutcome {
    /** Cancelled and refunded in full. The default. */
    REFUND,
    /** Marked no-shows - the driver keeps the fare. */
    NO_SHOW
}

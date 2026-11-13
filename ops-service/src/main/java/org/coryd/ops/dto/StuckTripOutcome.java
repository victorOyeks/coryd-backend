package org.coryd.ops.dto;

/** How ops resolves a stuck trip. */
public enum StuckTripOutcome {
    /** It happened - settle it like the driver's Complete Trip. */
    COMPLETED,
    /** It didn't - cancel it and refund every paid rider. */
    CANCELLED
}

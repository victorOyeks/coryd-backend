package org.coryd.trip.entity;

/** Who or what ended a trip or a seat - the Cancellations Log's "cancelled by" column. */
public enum CancellationSource {
    /** The rider cancelled their own seat. */
    RIDER,
    /** The driver cancelled their trip. */
    DRIVER,
    /** RouteExpiryJob: the driver never started the trip. */
    SYSTEM_EXPIRY,
    /** A payment that landed after the seat could no longer be given - refunded instead. */
    SYSTEM,
    /** An ops admin, through the Ops Console. */
    OPS,
    /** Ops suspended the rider or the driver. */
    SUSPENSION,
    /** The rider or driver closed their account. */
    ACCOUNT_DELETED;

    /** The cancelledBy carried on BOOKING_CANCELLED / ROUTE_CANCELLED, mapped to who did it. */
    public static CancellationSource fromCancelledBy(String cancelledBy) {
        if (cancelledBy == null) {
            return SYSTEM;
        }
        return switch (cancelledBy) {
            case "RIDER" -> RIDER;
            case "DRIVER" -> DRIVER;
            case "NOT_STARTED" -> SYSTEM_EXPIRY;
            case "OPS", "OPS_TRIP" -> OPS;
            case "SUSPENDED" -> SUSPENSION;
            case "ACCOUNT_DELETED" -> ACCOUNT_DELETED;
            default -> SYSTEM;
        };
    }

    /** The fault when nobody states one: the person who cancelled, the driver for a trip never started, the platform for
     * its own refunds. Suspensions and closed accounts aren't anyone's fault in the refund sense - they're refunded in full. */
    public CancellationFault defaultFault() {
        return switch (this) {
            case RIDER -> CancellationFault.RIDER;
            case DRIVER, SYSTEM_EXPIRY -> CancellationFault.DRIVER;
            case SYSTEM -> CancellationFault.PLATFORM;
            case OPS, SUSPENSION, ACCOUNT_DELETED -> null;
        };
    }
}

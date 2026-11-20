package org.coryd.trip.dto;

import org.coryd.trip.entity.CancellationLog;

/**
 * Who ended a booking, as the rider and driver apps word it. The status alone can't say: CANCELLED_DRIVER also covers
 * a trip CoRyd support cancelled, and an ops cancel with the rider at fault ends CANCELLED_RIDER.
 */
public enum CancelledBy {
    RIDER,
    DRIVER,
    /** CoRyd support (Ops Console), a suspension, or the platform itself - a trip never started, a payment too late. */
    CORYD;

    /** From the Cancellations Log row. A closed account is put on whoever closed it. */
    public static CancelledBy of(CancellationLog row) {
        return switch (row.getSource()) {
            case RIDER -> RIDER;
            case DRIVER -> DRIVER;
            case ACCOUNT_DELETED -> row.getActorId() != null && row.getActorId().equals(row.getRiderId()) ? RIDER : DRIVER;
            case OPS, SUSPENSION, SYSTEM, SYSTEM_EXPIRY -> CORYD;
        };
    }
}

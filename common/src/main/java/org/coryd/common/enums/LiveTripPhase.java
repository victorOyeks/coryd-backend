package org.coryd.common.enums;

/** Ops Console live monitor - ACTIVE once the driver starts the trip, BOARDING while it departs within
 * 30 minutes but hasn't started yet. */
public enum LiveTripPhase {
    ACTIVE,
    BOARDING
}

package org.coryd.common.enums;

/** The Drivers Directory's sortable columns. */
public enum DriverDirectorySort {
    DRIVER_REF,
    NAME,
    VEHICLE,
    TRIPS,
    RATING,
    EARNINGS,
    RELIABILITY,
    STATUS,
    JOINED,
    /** When they last completed a trip as the driver - drivers who never have go last. */
    LAST_TRIP
}

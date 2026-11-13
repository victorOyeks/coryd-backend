package org.coryd.common.enums;

/** The Riders Directory's sortable columns. RETENTION and RELIABILITY are the same number. */
public enum RiderDirectorySort {
    RIDER_REF,
    NAME,
    TRIPS,
    RETENTION,
    RELIABILITY,
    RATING,
    TOTAL_SPENT,
    REPORTS,
    STATUS,
    JOINED,
    /** When they last completed a trip - riders who never have go last. */
    LAST_TRIP
}

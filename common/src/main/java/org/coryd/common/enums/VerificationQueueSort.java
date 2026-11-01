package org.coryd.common.enums;

/** How the ops verification queue is ordered - the sortable column headers. */
public enum VerificationQueueSort {
    /** Longest-waiting first by default - "In queue for". */
    WAITING_SINCE,
    /** When the driver first submitted anything still on their record. */
    SUBMITTED_AT,
    NAME,
    DRIVER_REF
}

package org.coryd.common.enums;

/** Where an SOS (or an unanswered safety check in the SOS queue) is in ops' hands. */
public enum SosStatus {
    /** Nobody in ops has picked it up yet - re-alerts keep going out. */
    OPEN,
    /** An admin claimed it and is handling it. */
    ACKNOWLEDGED,
    /** An emergency service or contact was called. */
    ESCALATED,
    RESOLVED
}

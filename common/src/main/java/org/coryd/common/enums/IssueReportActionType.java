package org.coryd.common.enums;

/** What the outcome links to - an action ops already took through its own endpoint, referenced, never repeated. */
public enum IssueReportActionType {
    /** Ops cancelled a booking or a trip with a refund (POST /ops/bookings/{id}/cancel or /ops/trips/{id}/cancel): the audit id it returned. */
    BOOKING_CANCELLATION,
    /** A refund in payment-service: its refund id or reference. */
    REFUND,
    /** Ops suspended the account (POST /ops/users/{userId}/suspend): the user id suspended. */
    SUSPENSION,
    OTHER
}

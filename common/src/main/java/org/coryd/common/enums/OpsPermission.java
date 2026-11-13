package org.coryd.common.enums;

/**
 * What an ops admin may do in the console, grouped the way ops-service enforces it (one permission per endpoint group).
 * Which ops role holds which is {@link OpsRole#permissions()}; ops-service's OpsAccessPolicy maps every endpoint to one.
 * {@link #refusal()} finishes the 403 "Your ops role (FINANCE) can't ..." sentence.
 */
public enum OpsPermission {
    /** Invite admins, change their ops role, deactivate and reactivate them. */
    TEAM_MANAGE("manage the admin team"),
    /** The unified audit log and its CSV export. */
    AUDIT_VIEW("view the audit log"),
    /** Identity (NIN) checks, the driver verification queue, documents and document expiry - read. */
    VERIFICATION_VIEW("view identity checks or driver verification"),
    /** Approve, reject, request resubmission, give tries back, remind about expiring documents. */
    VERIFICATION_DECIDE("decide identity checks or driver documents"),
    /** Riders and drivers directories, profiles, account history and deletion requests - read only. */
    DIRECTORY_VIEW("view riders and drivers"),
    /** Suspend, reinstate, sign out everywhere, edit a person's details or vehicle. */
    ACCOUNT_MANAGE("suspend, reinstate, sign out or edit accounts"),
    /** Delete an account. */
    ACCOUNT_DELETE("delete accounts"),
    /** Trips, live trips, stuck trips, cancellations - read. */
    TRIPS_VIEW("view trips and bookings"),
    /** Cancel a trip or a booking, resolve a stuck trip. */
    TRIPS_MANAGE("cancel or resolve trips and bookings"),
    /** SOS queue, reports & disputes, ratings - read. */
    SAFETY_VIEW("view SOS, reports or ratings"),
    /** Acknowledge, escalate and resolve SOS; work reports; hide and restore ratings. */
    SAFETY_MANAGE("act on SOS, reports or ratings"),
    /** Transactions, refunds, approvals, payouts, earnings and debt, the finance dashboard, pricing - read. */
    MONEY_VIEW("view money"),
    /** Manual refunds, approvals, payout and refund retries, debt adjustments, revealing bank details. */
    MONEY_MANAGE("move money (refunds, approvals, payouts, debt) or reveal bank details"),
    /** The beta waitlist and marking people invited. */
    GROWTH_MANAGE("manage the beta waitlist"),

    INSIGHTS_VIEW("view the dashboard, analytics reports and corridors"),

    PLATFORM_MANAGE("change platform settings, send broadcasts or emailed reports, or view system health"),
    /** Dashboard metrics and the live stream - every ops role. */
    CONSOLE_BASICS("use the console");

    private final String refusal;

    OpsPermission(String refusal) {
        this.refusal = refusal;
    }

    /** "manage the admin team" - after "Your ops role (X) can't ". */
    public String refusal() {
        return refusal;
    }
}

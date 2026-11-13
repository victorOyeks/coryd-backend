package org.coryd.common.kafka;

/** Every Kafka topic name in CoRyd, in one place so a producer and its consumer(s) can never drift apart on spelling. */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String OTP_ISSUED = "coryd-otp-issued";

    public static final String ROUTE_PUBLISHED = "coryd-route-published";
    public static final String ROUTE_CANCELLED = "coryd-route-cancelled";
    public static final String NOTIFY_ME_MATCHED = "coryd-notify-me-matched";
    public static final String DRIVER_DEMAND_ALERT = "coryd-driver-demand-alert";
    public static final String DRIVER_NEARBY_DEMAND = "coryd-driver-nearby-demand";
    public static final String SEAT_RESERVED = "coryd-seat-reserved";
    public static final String ROUTE_DEPARTURE_REMINDER = "coryd-route-departure-reminder";
    public static final String ROUTE_TIME_CHANGED = "coryd-route-time-changed";

    public static final String BOOKING_CONFIRMED = "coryd-booking-confirmed";
    public static final String BOOKING_CANCELLED = "coryd-booking-cancelled";
    public static final String DRIVER_EN_ROUTE = "coryd-driver-en-route";
    public static final String DRIVER_ARRIVED = "coryd-driver-arrived";
    public static final String TRIP_COMPLETED = "coryd-trip-completed";
    public static final String SOS_TRIGGERED = "coryd-sos-triggered";
    public static final String ISSUE_REPORTED = "coryd-issue-reported";
    public static final String TRIP_SHARE_REQUESTED = "coryd-trip-share-requested";

    public static final String PAYMENT_SUCCEEDED = "coryd-payment-succeeded";
    public static final String PAYMENT_FAILED = "coryd-payment-failed";
    public static final String REFUND_ISSUED = "coryd-refund-issued";
    public static final String DRIVER_OWED_CHANGED = "coryd-driver-owed-changed";
    public static final String DRIVER_STATS_CHANGED = "coryd-driver-stats-changed";
    public static final String DRIVER_EARNINGS_CHANGED = "coryd-driver-earnings-changed";
    public static final String RIDER_STATS_CHANGED = "coryd-rider-stats-changed";
    public static final String PAYOUT_PROCESSED = "coryd-payout-processed";

    public static final String DRIVER_APPROVED = "coryd-driver-approved";
    public static final String DRIVER_REJECTED = "coryd-driver-rejected";
    public static final String VERIFICATION_ITEM_DECIDED = "coryd-verification-item-decided";
    public static final String DOCUMENT_EXPIRING = "coryd-document-expiring";

    /** Fired once, the first time a DriverProfile/RiderProfile row is ever created (not on later
     * edits) - trip-service listens to seed each new driver/rider's system starting rating. */
    public static final String DRIVER_PROFILE_CREATED = "coryd-driver-profile-created";
    public static final String RIDER_PROFILE_CREATED = "coryd-rider-profile-created";

    /** Ops suspended or reinstated an account - trip-service stops (or lets again) the user post and book. */
    public static final String USER_SUSPENDED = "coryd-user-suspended";
    public static final String USER_REINSTATED = "coryd-user-reinstated";
    public static final String USER_DELETED = "coryd-user-deleted";

    /**
     * Compacted, keyed by userId: the latest session state per user (tokens valid after, suspended). Every service's
     * JwtFilter replays it at startup and follows it live, so a suspension or "sign out everywhere" ends every access token
     * within seconds instead of at its expiry. See UserTokensRevokedEvent.
     */
    public static final String USER_TOKENS_REVOKED = "coryd-user-tokens-revoked";

    /** A person's NIN + selfie check was approved or rejected - trip-service gates booking and posting on it. */
    public static final String IDENTITY_VERIFICATION_CHANGED = "coryd-identity-verification-changed";

    /** A trip looks wrong (long stop, off route, no signal) - everyone on board is asked "Are you OK?". */
    public static final String TRIP_SAFETY_CHECK = "coryd-trip-safety-check";
    /** Nobody answered in time - ops is alerted. */
    public static final String TRIP_SAFETY_ESCALATED = "coryd-trip-safety-escalated";

    /** Ops Console (phase 5): an SOS changed in ops' hands - acknowledged, noted, escalated, resolved, re-alerted - or an
     * unanswered safety check opened as an SOS-queue incident. ops-service streams it to every open console. */
    public static final String OPS_SOS_CHANGED = "coryd-ops-sos-changed";
    /** The console's live badges (SOS by status, stuck trips, open reports) - sent by trip-service when they change. */
    public static final String OPS_COUNTS_CHANGED = "coryd-ops-counts-changed";
    /** An SOS nobody in ops has acknowledged after a threshold - notification-service alerts ops again. */
    public static final String SOS_OPS_REALERT = "coryd-sos-ops-realert";
    /** Ops resolved or dismissed an issue report - the reporter is told, with ops' message. */
    public static final String ISSUE_REPORT_UPDATED = "coryd-issue-report-updated";

    /**
     * Compacted, keyed by setting key: the platform settings ops changed in the console (commission, cancellation policy,
     * booking window...). trip-service owns them; every service replays the topic at startup and follows it live, so a
     * change reaches every service within seconds. See PlatformSettingChangedEvent and docs/ops-console/settings.md.
     */
    public static final String PLATFORM_SETTINGS = "coryd-platform-settings";
}

package org.coryd.common.kafka;

/** Every Kafka topic name in CoRyd, in one place so a producer and its consumer(s) can never drift apart on spelling. */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String OTP_ISSUED = "coryd-otp-issued";

    public static final String ROUTE_PUBLISHED = "coryd-route-published";
    public static final String ROUTE_CANCELLED = "coryd-route-cancelled";
    public static final String NOTIFY_ME_MATCHED = "coryd-notify-me-matched";
    public static final String SEAT_RESERVED = "coryd-seat-reserved";
    public static final String ROUTE_DEPARTURE_REMINDER = "coryd-route-departure-reminder";

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
    public static final String PAYOUT_PROCESSED = "coryd-payout-processed";

    public static final String DRIVER_APPROVED = "coryd-driver-approved";
    public static final String DRIVER_REJECTED = "coryd-driver-rejected";
    public static final String VERIFICATION_ITEM_DECIDED = "coryd-verification-item-decided";
    public static final String DOCUMENT_EXPIRING = "coryd-document-expiring";

    /** Fired once, the first time a DriverProfile/RiderProfile row is ever created (not on later
     * edits) - trip-service listens to seed each new driver/rider's system starting rating. */
    public static final String DRIVER_PROFILE_CREATED = "coryd-driver-profile-created";
    public static final String RIDER_PROFILE_CREATED = "coryd-rider-profile-created";
}

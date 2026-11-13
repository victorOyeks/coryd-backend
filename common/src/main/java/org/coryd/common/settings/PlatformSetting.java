package org.coryd.common.settings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every business rule ops can change at runtime from the Ops Console (docs/ops-console/settings.md). trip-service stores the
 * values and publishes them; every service reads them through {@link PlatformSettings}, falling back to its own property
 * (the env var it always had) when ops never set one. Keys are at most 36 characters - they double as the audit log's
 * target id.
 *
 * <p>{@code legal}: the value is in the published Terms of Service (PRD Appendix B) - changing it changes what CoRyd has
 * promised its users, so the console warns and should schedule it rather than apply it on the spot.
 */
public enum PlatformSetting {

    COMMISSION_PERCENT("commission.percent", Group.MONEY, Type.DECIMAL, "%", "0", "25", true, "payment-service",
            "Platform commission",
            "CoRyd's share of each fare, deducted from the driver's payout. Applies to charges and earnings from the moment it takes effect - never to bookings already paid.",
            "COMMISSION_PERCENTAGE (as a fraction, 0.05 = 5%)"),
    MONEY_APPROVAL_THRESHOLD("money.approval-threshold", Group.MONEY, Type.DECIMAL, "NGN", "1000", "5000000", false, "payment-service",
            "Second-admin approval threshold",
            "A manual refund, refund retry, payout retry or debt adjustment for more than this waits for a second admin.",
            "OPS_MONEY_APPROVAL_THRESHOLD"),
    LATE_CANCEL_FEE_PERCENT("cancellation.late-fee-percent", Group.CANCELLATION, Type.DECIMAL, "%", "0", "50", true, "trip-service",
            "Rider late-cancellation fee",
            "Share of the fare a rider pays when they cancel inside the free-cancellation window.",
            "cancellation.late-fee-percentage (as a fraction)"),
    FREE_CANCEL_WINDOW_HOURS("cancellation.free-window-hours", Group.CANCELLATION, Type.INTEGER, "hours", "0", "48", true, "trip-service",
            "Free-cancellation window",
            "A rider cancelling at least this long before departure pays nothing.",
            "cancellation.free-cancellation-window-hours"),
    BOOKING_GRACE_MINUTES("booking.grace-minutes", Group.BOOKING, Type.INTEGER, "minutes", "0", "60", false, "trip-service",
            "Booking window after departure",
            "Riders can book until the driver taps Start, or this many minutes after the departure time if they haven't.",
            "BOOKING_GRACE_MINUTES"),
    STUCK_TRIP_GRACE_HOURS("trips.stuck-grace-hours", Group.TRIPS, Type.INTEGER, "hours", "1", "48", false, "trip-service",
            "Stuck-trip grace",
            "Hours past a trip's expected arrival before an in-progress trip is flagged as stuck for ops.",
            "trip.stuck.grace-hours"),
    SOS_REALERT_MINUTES("safety.sos-realert-minutes", Group.SAFETY, Type.INTEGER_LIST, "minutes", "1", "240", false, "trip-service",
            "SOS re-alerts",
            "Minutes after an SOS is raised at which ops is alerted again if nobody has acknowledged it - once per threshold, at most 6.",
            "SOS_REALERT_MINUTES"),
    FARE_TOLERANCE_PERCENT("pricing.fare-tolerance-percent", Group.PRICING, Type.DECIMAL, "%", "0", "100", false, "trip-service",
            "Suggested-fare range tolerance",
            "How far outside a corridor's suggested range a posted fare can be before the pricing monitor flags it as an outlier.",
            "none (0 - flag anything outside the range)"),
    DOCUMENT_REMINDER_DAYS("documents.expiry-reminder-days", Group.DOCUMENTS, Type.INTEGER, "days", "7", "60", false, "profile-service",
            "Document expiry: first reminder",
            "Days before an insurance or roadworthiness certificate expires that the driver gets the first reminder - and that the tracker counts it as expiring soon.",
            "DOCUMENT_EXPIRY_REMINDER_DAYS"),
    DOCUMENT_FINAL_REMINDER_DAYS("documents.final-reminder-days", Group.DOCUMENTS, Type.INTEGER, "days", "1", "7", false, "profile-service",
            "Document expiry: final reminder",
            "Days before expiry that the driver gets the last reminder. Must be shorter than the first reminder.",
            "DOCUMENT_EXPIRY_FINAL_REMINDER_DAYS");

    public enum Group { MONEY, CANCELLATION, BOOKING, TRIPS, SAFETY, PRICING, DOCUMENTS }

    public enum Type { INTEGER, DECIMAL, INTEGER_LIST }

    public static final int MAX_LIST_SIZE = 6;

    private final String key;
    private final Group group;
    private final Type type;
    private final String unit;
    private final BigDecimal min;
    private final BigDecimal max;
    private final boolean legal;
    private final String usedBy;
    private final String label;
    private final String description;
    private final String source;

    PlatformSetting(String key, Group group, Type type, String unit, String min, String max, boolean legal, String usedBy,
                    String label, String description, String source) {
        this.key = key;
        this.group = group;
        this.type = type;
        this.unit = unit;
        this.min = new BigDecimal(min);
        this.max = new BigDecimal(max);
        this.legal = legal;
        this.usedBy = usedBy;
        this.label = label;
        this.description = description;
        this.source = source;
    }

    public String key() {
        return key;
    }

    public Group group() {
        return group;
    }

    public Type type() {
        return type;
    }

    public String unit() {
        return unit;
    }

    public BigDecimal min() {
        return min;
    }

    public BigDecimal max() {
        return max;
    }

    public boolean legal() {
        return legal;
    }

    public String usedBy() {
        return usedBy;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String source() {
        return source;
    }

    public static Optional<PlatformSetting> byKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }

    /**
     * The value in its one stored spelling ("5", "2.5", "5,15,30"), or an IllegalArgumentException whose message ops can
     * read. Integer lists are sorted, de-duplicated and every entry checked against the range.
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " needs a value");
        }
        String value = raw.trim();
        return switch (type) {
            case INTEGER -> {
                BigDecimal number = number(value);
                if (number.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException(label + " must be a whole number of " + unit);
                }
                inRange(number);
                yield number.toBigInteger().toString();
            }
            case DECIMAL -> {
                BigDecimal number = number(value);
                if (number.stripTrailingZeros().scale() > 2) {
                    throw new IllegalArgumentException(label + " can have at most 2 decimal places");
                }
                inRange(number);
                yield number.stripTrailingZeros().toPlainString();
            }
            case INTEGER_LIST -> {
                List<Integer> items = new ArrayList<>();
                for (String part : value.split(",")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    BigDecimal number = number(part.trim());
                    if (number.stripTrailingZeros().scale() > 0) {
                        throw new IllegalArgumentException(label + " must be whole numbers of " + unit);
                    }
                    inRange(number);
                    items.add(number.intValueExact());
                }
                List<Integer> sorted = items.stream().distinct().sorted().toList();
                if (sorted.isEmpty()) {
                    throw new IllegalArgumentException(label + " needs at least one value");
                }
                if (sorted.size() > MAX_LIST_SIZE) {
                    throw new IllegalArgumentException(label + " can have at most " + MAX_LIST_SIZE + " values");
                }
                yield String.join(",", sorted.stream().map(String::valueOf).toList());
            }
        };
    }

    private BigDecimal number(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number" + (type == Type.INTEGER_LIST ? " list like 5,15,30" : ""));
        }
    }

    private void inRange(BigDecimal number) {
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw new IllegalArgumentException(label + " must be between " + min.toPlainString() + " and " + max.toPlainString()
                    + " " + unit);
        }
    }
}

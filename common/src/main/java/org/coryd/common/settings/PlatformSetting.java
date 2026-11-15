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
            "DOCUMENT_EXPIRY_FINAL_REMINDER_DAYS"),
    ONBOARDING_REMINDERS_ENABLED("onboarding.reminders-enabled", Group.ONBOARDING, Type.BOOLEAN, "", null, null, false,
            "notification-service",
            "Onboarding reminders",
            "Remind riders and drivers who haven't finished signing up, by push and in their notifications, naming the next thing they need to do. Off: nobody is reminded.",
            "ONBOARDING_REMINDERS_ENABLED"),
    ONBOARDING_REMINDER_TIME("onboarding.reminder-time", Group.ONBOARDING, Type.TIME_OF_DAY, "Lagos time", null, null, false,
            "notification-service",
            "Onboarding reminder time",
            "When the daily onboarding reminders go out (24-hour HH:mm, Lagos time). Never inside the broadcast quiet hours - a time inside them waits until they end.",
            "ONBOARDING_REMINDER_TIME"),
    ONBOARDING_REMINDER_DAILY_DAYS("onboarding.reminder-daily-days", Group.ONBOARDING, Type.INTEGER, "days", "1", "60", false,
            "notification-service",
            "Onboarding reminders: daily for",
            "Days after sign-up (or after their last onboarding progress) that someone is reminded every day.",
            "ONBOARDING_REMINDER_DAILY_DAYS"),
    ONBOARDING_REMINDER_EVERY_DAYS("onboarding.reminder-then-every-days", Group.ONBOARDING, Type.INTEGER, "days", "1", "30", false,
            "notification-service",
            "Onboarding reminders: then every",
            "After the daily stretch, how many days apart the reminders are - until they finish signing up.",
            "ONBOARDING_REMINDER_EVERY_DAYS"),
    VERIFICATION_DIGEST_ENABLED("verification.digest-enabled", Group.NOTIFICATIONS, Type.BOOLEAN, "", null, null, false,
            "notification-service",
            "Verification morning digest",
            "Email verification officers each morning a list of everyone waiting for a review. Nothing is sent on a day nobody is waiting.",
            "VERIFICATION_DIGEST_ENABLED"),
    VERIFICATION_DIGEST_TIME("verification.digest-time", Group.NOTIFICATIONS, Type.TIME_OF_DAY, "Lagos time", null, null, false,
            "notification-service",
            "Verification digest time",
            "When the verification digest email goes out (24-hour HH:mm, Lagos time).",
            "VERIFICATION_DIGEST_TIME"),
    VERIFICATION_DIGEST_HIGHLIGHT_DAYS("verification.digest-highlight-days", Group.NOTIFICATIONS, Type.INTEGER, "days", "1", "30", false,
            "notification-service",
            "Verification digest: highlight after",
            "Anyone who has been waiting longer than this many days is highlighted in the digest.",
            "VERIFICATION_DIGEST_HIGHLIGHT_DAYS"),
    VERIFICATION_DIGEST_EXTRA_RECIPIENTS("verification.digest-extra-recipients", Group.NOTIFICATIONS, Type.EMAIL_LIST, "addresses", null, null,
            false, "notification-service",
            "Verification digest: also send to",
            "Email addresses that get the digest as well as every active Verification and Super admin - comma-separated, at most 10. \"none\" for nobody.",
            "VERIFICATION_DIGEST_EXTRA_RECIPIENTS");

    public enum Group { MONEY, CANCELLATION, BOOKING, TRIPS, SAFETY, PRICING, DOCUMENTS, ONBOARDING, NOTIFICATIONS }

    /**
     * How a value is spelled and checked. BOOLEAN: "true" / "false". TIME_OF_DAY: "HH:mm" (24-hour). EMAIL_LIST: lower-case
     * addresses, sorted and de-duplicated, comma-separated - or {@link #NONE} for an empty list. min/max apply to the numeric
     * types only (null for the others).
     */
    public enum Type { INTEGER, DECIMAL, INTEGER_LIST, BOOLEAN, TIME_OF_DAY, EMAIL_LIST }

    public static final int MAX_LIST_SIZE = 6;

    /** An EMAIL_LIST holds at most this many addresses. */
    public static final int MAX_EMAILS = 10;

    /** The EMAIL_LIST spelling of "no addresses". */
    public static final String NONE = "none";

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");
    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

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
        this.min = min == null ? null : new BigDecimal(min);
        this.max = max == null ? null : new BigDecimal(max);
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
            case BOOLEAN -> switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "true", "yes", "on", "1" -> "true";
                case "false", "no", "off", "0" -> "false";
                default -> throw new IllegalArgumentException(label + " must be true or false");
            };
            case TIME_OF_DAY -> {
                java.util.regex.Matcher m = TIME.matcher(value);
                if (!m.matches()) {
                    throw new IllegalArgumentException(label + " must be a 24-hour time like 08:00");
                }
                yield String.format("%02d:%s", Integer.parseInt(m.group(1)), m.group(2));
            }
            case EMAIL_LIST -> {
                if (NONE.equalsIgnoreCase(value)) {
                    yield NONE;
                }
                java.util.TreeSet<String> addresses = new java.util.TreeSet<>();
                for (String part : value.split("[,;\\s]+")) {
                    String address = part.trim().toLowerCase(java.util.Locale.ROOT);
                    if (address.isEmpty()) {
                        continue;
                    }
                    if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
                        throw new IllegalArgumentException("'" + part.trim() + "' isn't an email address");
                    }
                    addresses.add(address);
                }
                if (addresses.isEmpty()) {
                    throw new IllegalArgumentException(label + " needs at least one address, or \"none\"");
                }
                if (addresses.size() > MAX_EMAILS) {
                    throw new IllegalArgumentException(label + " can have at most " + MAX_EMAILS + " addresses");
                }
                String joined = String.join(",", addresses);
                if (joined.length() > 1000) {
                    throw new IllegalArgumentException(label + " is too long - at most 1000 characters");
                }
                yield joined;
            }
        };
    }

    /** "08:00" as a time - for TIME_OF_DAY values already normalized. */
    public static java.time.LocalTime parseTime(String value) {
        java.util.regex.Matcher m = TIME.matcher(value == null ? "" : value.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a time of day: " + value);
        }
        return java.time.LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** An EMAIL_LIST value as its addresses - empty for null, blank or {@link #NONE}. */
    public static List<String> parseEmails(String value) {
        if (value == null || value.isBlank() || NONE.equalsIgnoreCase(value.trim())) {
            return List.of();
        }
        return Arrays.stream(value.split("[,;\\s]+")).map(String::trim).filter(v -> !v.isEmpty())
                .map(v -> v.toLowerCase(java.util.Locale.ROOT)).distinct().toList();
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

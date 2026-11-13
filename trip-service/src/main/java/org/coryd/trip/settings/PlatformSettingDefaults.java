package org.coryd.trip.settings;

import org.coryd.common.settings.PlatformSetting;
import org.coryd.trip.config.FareProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.SosOpsProperties;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

/**
 * What each setting is when ops hasn't set it - the configured property (env var) of the service that uses it. trip-service's
 * own rules come from its properties beans; the rest from the same env vars payment- and profile-service read (every service
 * shares one env file on the VPS).
 */
@Component
public class PlatformSettingDefaults {

    private final CancellationPolicy cancellationPolicy;
    private final MatchingProperties matchingProperties;
    private final StuckTripProperties stuckTripProperties;
    private final SosOpsProperties sosOpsProperties;
    private final FareProperties fareProperties;
    private final BigDecimal commissionFraction;
    private final BigDecimal approvalThreshold;
    private final int documentReminderDays;
    private final int documentFinalReminderDays;

    public PlatformSettingDefaults(CancellationPolicy cancellationPolicy, MatchingProperties matchingProperties,
                                   StuckTripProperties stuckTripProperties, SosOpsProperties sosOpsProperties,
                                   FareProperties fareProperties,
                                   @Value("${coryd.platform-settings.defaults.commission-fraction:0.05}") BigDecimal commissionFraction,
                                   @Value("${coryd.platform-settings.defaults.money-approval-threshold:50000}") BigDecimal approvalThreshold,
                                   @Value("${coryd.platform-settings.defaults.document-reminder-days:14}") int documentReminderDays,
                                   @Value("${coryd.platform-settings.defaults.document-final-reminder-days:3}") int documentFinalReminderDays) {
        this.cancellationPolicy = cancellationPolicy;
        this.matchingProperties = matchingProperties;
        this.stuckTripProperties = stuckTripProperties;
        this.sosOpsProperties = sosOpsProperties;
        this.fareProperties = fareProperties;
        this.commissionFraction = commissionFraction;
        this.approvalThreshold = approvalThreshold;
        this.documentReminderDays = documentReminderDays;
        this.documentFinalReminderDays = documentFinalReminderDays;
    }

    /** In the setting's own unit and spelling ("5" for 5%, "5,15,30"). */
    public String of(PlatformSetting setting) {
        return switch (setting) {
            case COMMISSION_PERCENT -> plain(commissionFraction.movePointRight(2));
            case MONEY_APPROVAL_THRESHOLD -> plain(approvalThreshold);
            case LATE_CANCEL_FEE_PERCENT -> plain(cancellationPolicy.configuredLateFeePercentage().movePointRight(2));
            case FREE_CANCEL_WINDOW_HOURS -> String.valueOf(cancellationPolicy.configuredFreeCancellationWindowHours());
            case BOOKING_GRACE_MINUTES -> String.valueOf(matchingProperties.configuredBookingGraceMinutes());
            case STUCK_TRIP_GRACE_HOURS -> String.valueOf(stuckTripProperties.configuredGraceHours());
            case SOS_REALERT_MINUTES -> sosOpsProperties.configuredRealertMinutes().stream().filter(m -> m != null && m > 0)
                    .sorted().distinct().map(String::valueOf).collect(Collectors.joining(","));
            case FARE_TOLERANCE_PERCENT -> plain(fareProperties.getOutlierTolerancePercent() == null
                    ? BigDecimal.ZERO : fareProperties.getOutlierTolerancePercent());
            case DOCUMENT_REMINDER_DAYS -> String.valueOf(documentReminderDays);
            case DOCUMENT_FINAL_REMINDER_DAYS -> String.valueOf(documentFinalReminderDays);
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

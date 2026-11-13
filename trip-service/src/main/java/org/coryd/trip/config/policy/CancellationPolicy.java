package org.coryd.trip.config.policy;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Section 15 leaves the exact cancellation fee schedule open - default here is
 * "no fee outside the free window, a flat percentage fee inside it."
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "cancellation")
@Component
public class CancellationPolicy {

    private int freeCancellationWindowHours = 2;
    private BigDecimal lateFeePercentage = new BigDecimal("0.25");

    /** Ops' runtime values (docs/ops-console/settings.md) - absent in slice tests, where the properties apply. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private PlatformSettings platformSettings;

    /** The free window in effect: ops' value, else cancellation.free-cancellation-window-hours. */
    public int getFreeCancellationWindowHours() {
        return PlatformSettings.integer(platformSettings, PlatformSetting.FREE_CANCEL_WINDOW_HOURS, freeCancellationWindowHours);
    }

    /** The late fee in effect, as a fraction: ops' percentage / 100, else cancellation.late-fee-percentage. */
    public BigDecimal getLateFeePercentage() {
        BigDecimal percent = PlatformSettings.decimal(platformSettings, PlatformSetting.LATE_CANCEL_FEE_PERCENT, null);
        return percent == null ? lateFeePercentage : percent.movePointLeft(2);
    }

    /** The configured values, before ops' - the defaults the console shows. */
    public int configuredFreeCancellationWindowHours() {
        return freeCancellationWindowHours;
    }

    public BigDecimal configuredLateFeePercentage() {
        return lateFeePercentage;
    }

    public boolean isFeeApplicable(Instant departureTime, Instant now) {
        Duration untilDeparture = Duration.between(now, departureTime);
        return untilDeparture.toHours() < getFreeCancellationWindowHours();
    }

    public BigDecimal applyFee(BigDecimal fareTotal, boolean feeApplicable) {
        return feeApplicable ? fareTotal.multiply(getLateFeePercentage()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
}

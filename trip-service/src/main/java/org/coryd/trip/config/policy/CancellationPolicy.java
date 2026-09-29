package org.coryd.trip.config.policy;

import lombok.Getter;
import lombok.Setter;
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

    public boolean isFeeApplicable(Instant departureTime, Instant now) {
        Duration untilDeparture = Duration.between(now, departureTime);
        return untilDeparture.toHours() < freeCancellationWindowHours;
    }

    public BigDecimal applyFee(BigDecimal fareTotal, boolean feeApplicable) {
        return feeApplicable ? fareTotal.multiply(lateFeePercentage).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
}

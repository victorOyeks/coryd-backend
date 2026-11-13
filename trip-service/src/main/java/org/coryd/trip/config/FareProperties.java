package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** The fare suggested to drivers while posting - distance of their whole route times a rate. Only a suggestion:
 * the driver can keep it or set their own, and every rider pays the same per seat wherever they join. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.fare")
public class FareProperties {

    /** Naira per kilometre of the driver's road - Ikorodu to Victoria Island (39.6 km) comes to ₦4,000. */
    private BigDecimal perKm = new BigDecimal("100");

    /** No suggestion goes below this, so a short hop isn't suggested at a few hundred naira. */
    private BigDecimal minimum = new BigDecimal("500");

    /** Suggestions are rounded to the nearest this many naira. */
    private BigDecimal roundTo = new BigDecimal("100");

    /** Pricing monitor: how far outside a corridor's suggested range (in %) a fare can be before it's an outlier. */
    private BigDecimal outlierTolerancePercent = BigDecimal.ZERO;
}

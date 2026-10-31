package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * "You saved about N compared with a solo cab" on a rider's booking - an estimate of a solo ride-hailing fare for
 * the same distance. Tune to the market; enabled=false hides it.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.savings")
public class SavingsProperties {

    private boolean enabled = true;

    /** Naira a solo cab charges before distance. */
    private BigDecimal soloCabBaseFare = new BigDecimal("700");

    /** Naira per road km. */
    private BigDecimal soloCabPerKm = new BigDecimal("200");

    /** Road distance over straight-line distance - Lagos roads aren't straight. */
    private double roadFactor = 1.3;
}

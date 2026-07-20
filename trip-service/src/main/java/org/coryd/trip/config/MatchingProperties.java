package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Section 15 leaves the exact match tolerances as open Product questions -
 * kept config-driven here rather than hardcoded so they can be tuned per pilot
 * feedback without a redeploy.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    /** Default 20 minutes either side of the rider's desired departure time. */
    private int timeToleranceMinutes = 20;

    /** Default 500m from the driver's origin/destination points. */
    private int geoToleranceMeters = 500;

    /** Minimum published fares before a corridor gets a suggested-rate range (D-205). */
    private int minFareReports = 5;
}

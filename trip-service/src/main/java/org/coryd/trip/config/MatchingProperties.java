package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    /** Default 60 minutes either side of the rider's desired departure time. */
    private int timeToleranceMinutes = 60;

    /** Default 4km from the driver's origin/destination points. */
    private int geoToleranceMeters = 4000;

    /** Minimum published fares before a corridor gets a suggested-rate range (D-205). */
    private int minFareReports = 5;
}

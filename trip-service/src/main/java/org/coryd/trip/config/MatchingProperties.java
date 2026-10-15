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

    /** Either side of the rider's desired time - an hour suits commuters; wider shows trips they can't use. */
    private int timeToleranceMinutes = 60;

    /** From the driver's start and end, for end-to-end matches - riders further out are found along the way instead. */
    private int geoToleranceMeters = 2000;

    /** Also match riders whose start and end are both near the driver's road, in that order - not just near its ends. */
    private boolean alongRouteEnabled = true;

    /** How far a rider will walk to and from the driver's road - the driver never leaves it. */
    private int walkMeters = 1000;

    /** Ask PostGIS for along-the-way candidates when it's installed (GeoCapabilities); off forces the in-memory path. */
    private boolean usePostgis = true;

    /** Longest trip assumed when looking for routes that pass a rider later than they leave. */
    private int maxTripMinutes = 240;

    /** Minimum published fares before a corridor gets a suggested-rate range (D-205). */
    private int minFareReports = 5;
}

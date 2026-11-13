package org.coryd.trip.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * Riders can book until the driver taps Start, or this many minutes after the departure time if they haven't -
     * a driver still at the start can take another rider, but one who drove off without tapping Start mustn't be sold
     * seats for long.
     */
    private int bookingGraceMinutes = 10;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private PlatformSettings platformSettings;

    /** Ops' runtime value (booking.grace-minutes), else BOOKING_GRACE_MINUTES. */
    public int getBookingGraceMinutes() {
        return PlatformSettings.integer(platformSettings, PlatformSetting.BOOKING_GRACE_MINUTES, bookingGraceMinutes);
    }

    public int configuredBookingGraceMinutes() {
        return bookingGraceMinutes;
    }

    /** When booking closes on a trip leaving at departureTime, unless the driver starts it first. */
    public java.time.Instant bookableUntil(java.time.Instant departureTime) {
        return departureTime == null ? null : departureTime.plus(java.time.Duration.ofMinutes(getBookingGraceMinutes()));
    }
}

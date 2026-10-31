package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Telling a driver near waiting riders that they're going the driver's way. A driver's location is only known
 * while they have the app open - kept for presenceMinutes, never as a history.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.nearby-alert")
public class NearbyAlertProperties {

    private boolean enabled = true;

    /** Riders this close to where the driver last opened the app. */
    private int radiusMeters = 500;

    /** A driver's location counts for this long after the app last sent it. */
    private int presenceMinutes = 30;

    /** At most one push per driver this often. */
    private int cooldownMinutes = 30;

    /** ...and at most this many a day. */
    private int maxPerDay = 4;
}

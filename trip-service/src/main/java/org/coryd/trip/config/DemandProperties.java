package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Rider demand - what drivers see in "Riders are looking for", and who gets the evening demand push. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.demand")
public class DemandProperties {

    /** Size of the squares nearby points are grouped into, in degrees - 0.03 is about 3 km in Lagos. */
    private double cellDegrees = 0.03;

    /** A route only shows to drivers once this many different riders want it - fewer could identify someone. */
    private int minRidersShown = 3;

    /** Demand strong enough to push a driver about. */
    private int minRidersForAlert = 5;

    /** How close a driver's own trip has to start and end to count as the same route. */
    private double matchRadiusKm = 5.0;

    /** Riders' times are grouped into windows at most this wide - 6:50 and 7:10 are one demand, not two. */
    private int windowMinutes = 120;

    /** A driver's trip, or a posted ride, counts as the same time if it's within this much of the window. */
    private int timeSlackMinutes = 60;

    /** Searches from this many days back count towards demand. */
    private int searchWindowDays = 7;

    /** A driver's routes from this many days back say where they drive. */
    private int driverHistoryDays = 60;

    /** Searches are deleted after this many days. */
    private int retentionDays = 90;

    /** At most this many demand pushes per driver per week (and never more than one a day). */
    private int maxAlertsPerWeek = 3;

    /** A rider can have at most this many active "Notify me" requests. */
    private int maxActiveNotifyMe = 10;

    /** A new "Notify me" starting and ending this close to an active one of the rider's is the same route. */
    private double notifyMeDuplicateRadiusKm = 1.0;

    /** ...and within this much of its time is the same request - a second copy would only push twice per ride. */
    private int notifyMeDuplicateMinutes = 60;
}

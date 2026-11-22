package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** When a trip in progress looks wrong enough to ask everyone on board "Are you OK?". */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.safety")
public class TripSafetyProperties {

    private boolean enabled = true;

    /** Stopped = every location within this distance of where the car stopped... */
    private int stopRadiusMeters = 50;
    /** ...for this long... */
    private int stopMinutes = 10;
    /** ...and not near the start, the destination or a rider's pickup. */
    private int knownStopRadiusMeters = 300;

    /** Off route = this far from the line planned at departure (reroutes don't move it)... */
    private int offRouteMeters = 1000;
    /** ...for this long. */
    private int offRouteMinutes = 5;

    /** No location from the driver's phone for this long during a trip. */
    private int signalLostMinutes = 10;

    /** A stopped / off-route check unanswered for this long and ops is alerted. Long enough for a driver to finish a
     * manoeuvre and look at the phone - three minutes alerted ops about drivers who were simply driving. */
    private int answerMinutes = 10;

    /** A signal-lost check is never escalated for the silence alone - phones are locked, backgrounded and drop out of
     * coverage all the time. It goes to ops only if nobody has answered, the phone is still quiet and the trip is this
     * long past its expected arrival (start + estimated duration) without being completed. */
    private int signalLostOverdueMinutes = 60;

    /** The same kind of check on the same trip isn't raised again within this long - one alert, not a stream. */
    private int repeatMinutes = 60;

    /** A trip is only watched until its expected arrival (start + estimated duration) plus this long. Past that
     * it's a trip nobody completed, not a trip in danger - the stuck-trip job flags those for ops instead. */
    private int watchGraceMinutes = 60;
}

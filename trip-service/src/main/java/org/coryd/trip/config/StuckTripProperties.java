package org.coryd.trip.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.service.TripScheduleService;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

/**
 * When a trip left IN_PROGRESS counts as stuck: its expected arrival - the real start (or the planned departure if
 * there's none) plus Google's drive time, or an hour when Maps had none - plus graceHours. Only ops can resolve it:
 * nothing in the data says whether the trip happened and the driver forgot Complete, or it never ran.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.stuck")
public class StuckTripProperties {

    /** Hours past the expected arrival before an in-progress trip is flagged. */
    private int graceHours = 3;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private PlatformSettings platformSettings;

    /** Ops' runtime value (trips.stuck-grace-hours), else trip.stuck.grace-hours. */
    public int getGraceHours() {
        return PlatformSettings.integer(platformSettings, PlatformSetting.STUCK_TRIP_GRACE_HOURS, graceHours);
    }

    public int configuredGraceHours() {
        return graceHours;
    }

    public Instant expectedArrival(RoutePost route) {
        Instant start = route.getStartedAt() != null ? route.getStartedAt() : route.getDepartureTime();
        int minutes = route.getEstimatedDurationMinutes() == null
                ? TripScheduleService.DEFAULT_DURATION_MINUTES : route.getEstimatedDurationMinutes();
        return start.plus(Duration.ofMinutes(minutes));
    }

    /** The moment the trip became stuck - expected arrival plus the grace. */
    public Instant stuckFrom(RoutePost route) {
        return expectedArrival(route).plus(Duration.ofHours(getGraceHours()));
    }

    public boolean isStuck(RoutePost route, Instant now) {
        return route.getStatus() == RouteStatus.IN_PROGRESS && !now.isBefore(stuckFrom(route));
    }
}

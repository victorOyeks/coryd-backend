package org.coryd.trip.service;

import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.entity.RoutePost;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Along the way: both ends within a walk of the driver's road, in order, with a pickup time from how far along it is. */
class RouteMatcherTest {

    private static final Instant SEVEN = Instant.parse("2026-10-20T06:00:00Z");

    private final RouteMatcher matcher = new RouteMatcher(new MatchingProperties());
    /** Ikorodu to VI, straight (no saved road line), an hour's drive. */
    private final RoutePost route = RoutePost.builder().originLat(6.62).originLng(3.51).destinationLat(6.42).destinationLng(3.41)
            .departureTime(SEVEN).estimatedDurationMinutes(60).build();

    @Test
    void aRiderStartingAndEndingOnTheRoadMatchesWithAPickupPartWay() {
        var match = matcher.match(route, 6.57, 3.485, 6.47, 3.435).orElseThrow();

        assertThat(match.pickup().fraction()).isCloseTo(0.25, within(0.01));
        assertThat(match.dropoff().fraction()).isCloseTo(0.75, within(0.01));
        assertThat(Duration.between(SEVEN, match.pickup().time()).toMinutes()).isBetween(14L, 16L);
        assertThat(match.pickup().walkMeters()).isLessThan(50);
    }

    @Test
    void goingTheOtherWayDoesNotMatch() {
        assertThat(matcher.match(route, 6.47, 3.435, 6.57, 3.485)).isEmpty();
    }

    @Test
    void moreThanAWalkFromTheRoadDoesNotMatch() {
        // About 3 km east of the road at the pickup end.
        assertThat(matcher.match(route, 6.57, 3.515, 6.47, 3.435)).isEmpty();
        assertThat(matcher.metersFromRoute(route, 6.57, 3.515)).isGreaterThan(1000);
    }

    @Test
    void aRiderAtTheRoutesStartIsZeroAlong() {
        var match = matcher.match(route, 6.62, 3.51, 6.42, 3.41).orElseThrow();

        assertThat(match.pickup().fraction()).isZero();
        assertThat(match.pickup().time()).isEqualTo(SEVEN);
    }

    @Test
    void itCanBeSwitchedOff() {
        MatchingProperties off = new MatchingProperties();
        off.setAlongRouteEnabled(false);
        assertThat(new RouteMatcher(off).enabled()).isFalse();
    }
}

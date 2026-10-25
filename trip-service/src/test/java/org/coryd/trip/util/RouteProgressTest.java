package org.coryd.trip.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Stops are ordered by how far along the road they are - not by how close they look on the map. */
class RouteProgressTest {

    /** A road that goes east, turns north, then comes back west, ending near where it started. */
    private static final String U_ROAD = encode(new double[][]{{6.60, 3.40}, {6.60, 3.50}, {6.62, 3.50}, {6.62, 3.40}});

    @Test
    void aStopTheCarReachesFirstComesFirstEvenIfAnotherLooksCloserOnTheMap() {
        RouteProgress progress = RouteProgress.of(U_ROAD);
        double outbound = progress.pickup(6.60, 3.45);   // halfway along the first leg
        double inbound = progress.pickup(6.62, 3.41);    // near the end of the return leg - but close to the start as the crow flies

        assertThat(GeoMath.distanceKm(6.60, 3.40, 6.62, 3.41)).as("looks closer on the map")
                .isLessThan(GeoMath.distanceKm(6.60, 3.40, 6.60, 3.45));
        assertThat(outbound).as("reached first along the road").isLessThan(inbound);
        assertThat(outbound).isCloseTo(5_500, within(150.0));
    }

    @Test
    void boardingAtTheStartIsZeroAndRidingToTheEndIsTheWholeRoute() {
        RouteProgress progress = RouteProgress.of(U_ROAD);
        double whole = progress.dropoff(null, null, 0.0);

        assertThat(progress.pickup(null, null)).isZero();
        assertThat(whole).isCloseTo(11_100 + 2_200 + 11_100, within(300.0));
        assertThat(progress.dropoff(6.62, 3.40, 0.0)).isCloseTo(whole, within(5.0));
    }

    @Test
    void aDropoffIsNeverBeforeItsOwnPickup() {
        RouteProgress progress = RouteProgress.of(U_ROAD);
        double pickup = progress.pickup(6.61, 3.50);

        assertThat(progress.dropoff(6.60, 3.42, pickup)).isEqualTo(pickup);
    }

    @Test
    void aRouteWithoutALineGivesNoNumbersSoTheAppKeepsItsOrder() {
        assertThat(RouteProgress.of(null).pickup(6.6, 3.4)).isNull();
        assertThat(RouteProgress.of("").dropoff(null, null, null)).isNull();
    }

    /** Google's encoded polyline format, the same one routes are stored in. */
    private static String encode(double[][] points) {
        StringBuilder out = new StringBuilder();
        long prevLat = 0;
        long prevLng = 0;
        for (double[] p : points) {
            long lat = Math.round(p[0] * 1e5);
            long lng = Math.round(p[1] * 1e5);
            encodeValue(lat - prevLat, out);
            encodeValue(lng - prevLng, out);
            prevLat = lat;
            prevLng = lng;
        }
        return out.toString();
    }

    private static void encodeValue(long value, StringBuilder out) {
        long v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }
}

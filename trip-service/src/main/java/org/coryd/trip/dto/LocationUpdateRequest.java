package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;

/**
 * heading and speed are optional and straight from the phone's GPS (expo-location's coords.heading / coords.speed).
 * Out-of-range values - expo reports -1 when it doesn't know - are dropped rather than refused, so a ping is never lost over them.
 */
public record LocationUpdateRequest(@NotNull Double lat, @NotNull Double lng, Double heading, Double speed) {

    /** Degrees in [0, 360), or null when missing or not a real bearing. */
    public Double cleanHeading() {
        if (heading == null || heading.isNaN() || heading.isInfinite() || heading < 0 || heading > 360) {
            return null;
        }
        return heading == 360 ? 0.0 : heading;
    }

    /** Metres per second, or null when missing, negative or implausible (over 100 m/s). */
    public Double cleanSpeed() {
        if (speed == null || speed.isNaN() || speed.isInfinite() || speed < 0 || speed > 100) {
            return null;
        }
        return speed;
    }
}

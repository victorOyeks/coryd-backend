package org.coryd.trip.dto;

import java.time.Instant;

/** A point on the driver's road where the rider gets in or out - walkMeters from where they searched, time estimated. */
public record RideStop(double lat, double lng, int walkMeters, Instant time) {
}

package org.coryd.trip.dto;

import java.time.Instant;

/** A point on the driver's road where the rider gets in or out - walkMeters from where they searched, time estimated.
 * label is the place name the rider searched with (null if the app didn't send one) - show it on the card and send it
 * back as pickupLabel/dropoffLabel when booking, so the driver sees where this rider really gets in and out. */
public record RideStop(double lat, double lng, int walkMeters, Instant time, String label) {
}

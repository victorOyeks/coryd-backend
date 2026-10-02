package org.coryd.trip.dto;

import java.util.List;

/** Map pins + a count label ("3 rides near Ikorodu"); each pin opens that route's booking flow. */
public record NearbyActivityResponse(int count, List<NearbyRidePin> pins) {
}

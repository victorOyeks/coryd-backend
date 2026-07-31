package org.coryd.trip.dto;

import java.util.List;

/** Deliberately light - map pins + a count label ("3 rides near Ikorodu"), not a bookable list. */
public record NearbyActivityResponse(int count, List<GeoPin> pins) {
}

package org.coryd.trip.dto;

/** A user's own aggregate rating, as a ratee - works for either a rider's or a driver's Account screen, same underlying data. */
public record RatingSummaryResponse(Double ratingAvg, Long ratingCount) {

    public static final RatingSummaryResponse NONE = new RatingSummaryResponse(null, 0L);
}

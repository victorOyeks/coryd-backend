package org.coryd.trip.dto;

/** One row of a batched Google Distance Matrix call - null fields mean that particular origin/destination pair failed independently, not that the whole batch did. */
public record DistanceMatrixResult(Double distanceKm, Integer durationMinutes) {
}

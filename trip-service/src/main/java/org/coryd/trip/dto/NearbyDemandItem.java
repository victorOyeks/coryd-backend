package org.coryd.trip.dto;

/**
 * Riders waiting near the caller, going to one area. destinationLat/Lng is the middle of that area, never a
 * rider's own point; areaLabel is the place name most of them gave, or null if none did.
 */
public record NearbyDemandItem(String areaLabel, double destinationLat, double destinationLng, int waitingCount) {
}

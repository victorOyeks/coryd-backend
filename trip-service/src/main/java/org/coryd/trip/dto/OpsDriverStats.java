package org.coryd.trip.dto;

/**
 * One driver's numbers for the Ops Console's Drivers Directory. trips: rides completed on their trips.
 * reliabilityPercent: completed / (completed + paid seats the driver cancelled) - null before either happens.
 * reportsFiled: problems riders reported on their trips.
 */
public record OpsDriverStats(String driverId, long trips, Double ratingAvg, long ratingCount, Integer reliabilityPercent, long reportsFiled) {
}

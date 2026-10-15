package org.coryd.trip.event;

/** In-process only: a driver's location during a trip, for the safety checks (TripSafetyService). */
public record DriverLocationRecordedEvent(String routePostId, double lat, double lng) {
}

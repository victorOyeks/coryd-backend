package org.coryd.trip.event;

/**
 * In-process: a driver started their route, so it stops taking bookings. BookingService lets go of any favourite-rider
 * reservation still waiting for an answer, in the same transaction (M52).
 */
public record RouteStartedEvent(String routePostId) {
}

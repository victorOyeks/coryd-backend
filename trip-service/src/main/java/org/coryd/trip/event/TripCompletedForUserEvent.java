package org.coryd.trip.event;

import org.coryd.trip.entity.FirstTripExperience;

/**
 * In-process: someone just completed a trip in this role - a rider's seat (bookingId set) or a driver's route. Published
 * inside the completing transaction; FirstTripService looks after commit at whether it was their first.
 */
public record TripCompletedForUserEvent(FirstTripExperience.Role role, String userId, String routePostId, String bookingId) {
}

package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Service-to-service only (identity-service, before deleting an account) - gated by InternalApiKeyFilter
 * via the /internal/** prefix. Someone on a trip right now can't be deleted; upcoming trips don't block it.
 */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserDeletionController {

    /** The driver is on the way, waiting, or the rider is in the car. */
    private static final List<BookingStatus> ON_TRIP_NOW = List.of(
            BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;

    @GetMapping("/{userId}/deletion-blockers")
    @Transactional(readOnly = true)
    public ApiResponse<List<String>> deletionBlockers(@PathVariable String userId) {
        List<String> reasons = new ArrayList<>();
        if (!routePostRepository.findByDriverIdAndStatusIn(userId, List.of(RouteStatus.IN_PROGRESS)).isEmpty()) {
            reasons.add("you're driving a trip right now - finish it first");
        }
        if (!bookingRepository.findByRiderIdAndStatusIn(userId, ON_TRIP_NOW).isEmpty()) {
            reasons.add("you're on a trip right now - wait until it's finished");
        }
        return ApiResponse.ok(reasons);
    }
}

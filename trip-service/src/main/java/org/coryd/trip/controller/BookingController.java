package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.TripHistoryStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.BookingDetailsResponse;
import org.coryd.trip.dto.BookingStatusHistoryEntry;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.dto.RatingSummaryResponse;
import org.coryd.trip.dto.TripHistoryItem;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.service.BookingService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;


@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @GetMapping("/riders/{riderId}/recent-routes")
    public ApiResponse<List<Booking>> recentRoutes(@PathVariable String riderId) {
        return ApiResponse.ok(bookingService.getRecentRoutesForRider(riderId));
    }

    /** Powers the Home screen's "Go back to your current trip" banner. */
    @GetMapping("/riders/{riderId}/active")
    public ApiResponse<Optional<BookingDetailsResponse>> activeTrip(@PathVariable String riderId) {
        return ApiResponse.ok(bookingService.getActiveBookingForRider(riderId));
    }

    /** Trip History screen - status is omitted entirely for the "All" tab. */
    @GetMapping("/riders/{riderId}/history")
    public ApiResponse<PagedResponse<TripHistoryItem>> history(@PathVariable String riderId,
                                                                 @RequestParam(required = false) TripHistoryStatus status,
                                                                 @RequestParam(required = false) String search,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(bookingService.getHistory(riderId, status, search, pageable));
    }

    /** Account screen's star rating - works the same for a rider's or a driver's own id. */
    @GetMapping("/users/{userId}/rating-summary")
    public ApiResponse<RatingSummaryResponse> ratingSummary(@PathVariable String userId) {
        return ApiResponse.ok(bookingService.getRatingSummary(userId));
    }

    @GetMapping("/{bookingId}")
    public ApiResponse<BookingDetailsResponse> details(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.getBookingDetails(bookingId));
    }

    /** Track Live Trip's status timeline - each entry is a status the booking has passed through, with when it happened. */
    @GetMapping("/{bookingId}/history")
    public ApiResponse<List<BookingStatusHistoryEntry>> history(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.getStatusHistory(bookingId));
    }

    @PostMapping("/routes/{routePostId}")
    public ApiResponse<Booking> reserve(@PathVariable String routePostId, @Valid @RequestBody BookSeatsRequest request) {
        return ApiResponse.ok(bookingService.reserveSeats(routePostId, request));
    }

    @PostMapping("/{bookingId}/cancel")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.cancelByRider(bookingId, principal.getUserId());
        return ApiResponse.ok("Booking cancelled", null);
    }

    @PostMapping("/{bookingId}/en-route")
    public ApiResponse<Void> enRoute(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.markEnRouteToPickup(bookingId, principal.getUserId());
        return ApiResponse.ok("Driver marked en route to pickup", null);
    }

    @PostMapping("/{bookingId}/arrived-at-pickup")
    public ApiResponse<Void> arrivedAtPickup(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.markArrivedAtPickup(bookingId, principal.getUserId());
        return ApiResponse.ok("Driver marked arrived at pickup", null);
    }

    @PostMapping("/{bookingId}/picked-up")
    public ApiResponse<Void> pickedUp(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.markPickedUp(bookingId, principal.getUserId());
        return ApiResponse.ok("Rider marked as picked up", null);
    }

    @PostMapping("/{bookingId}/no-show")
    public ApiResponse<Void> noShow(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.markNoShow(bookingId, principal.getUserId());
        return ApiResponse.ok("Rider marked as no-show", null);
    }

    @PostMapping("/{bookingId}/complete")
    public ApiResponse<Void> complete(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.completeTrip(bookingId, principal.getUserId());
        return ApiResponse.ok("Trip completed", null);
    }

    @PostMapping("/{bookingId}/rate")
    public ApiResponse<Rating> rate(@PathVariable String bookingId, @Valid @RequestBody RateRequest request) {
        return ApiResponse.ok(bookingService.rate(bookingId, request));
    }

    @PostMapping("/{bookingId}/sos")
    public ApiResponse<SosIncident> sos(@PathVariable String bookingId,
                                         @RequestParam String triggeredBy,
                                         @RequestParam(required = false) Double latitude,
                                         @RequestParam(required = false) Double longitude) {
        return ApiResponse.ok(bookingService.triggerSos(bookingId, triggeredBy, latitude, longitude));
    }

    @PostMapping("/{bookingId}/share")
    public ApiResponse<String> share(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.shareTrip(bookingId));
    }

    /** Account > Report an issue - bookingId lives in the body (nullable) since a trip isn't always selected. */
    @PostMapping("/report-issue")
    public ApiResponse<IssueReport> reportIssue(@Valid @RequestBody IssueReportRequest request) {
        return ApiResponse.ok(bookingService.reportIssue(request));
    }
}

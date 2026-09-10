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
import org.coryd.trip.dto.BookingSummary;
import org.coryd.trip.dto.DriverActiveTripResponse;
import org.coryd.trip.dto.DriverTripHistoryItem;
import org.coryd.trip.dto.DriverTripStatsResponse;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.dto.RatingSummaryResponse;
import org.coryd.trip.dto.TripHistoryItem;
import org.coryd.trip.dto.TripReceiptResponse;
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

    /** Dashboard's "Active Trip" card. */
    @GetMapping("/drivers/{driverId}/active")
    public ApiResponse<Optional<DriverActiveTripResponse>> activeTripForDriver(@PathVariable String driverId) {
        return ApiResponse.ok(bookingService.getActiveTripForDriver(driverId));
    }

    /** Dashboard's "Trips this week" tile. */
    @GetMapping("/drivers/{driverId}/stats")
    public ApiResponse<DriverTripStatsResponse> statsForDriver(@PathVariable String driverId) {
        return ApiResponse.ok(bookingService.getStatsForDriver(driverId));
    }

    /** Driver Trip History screen - one row per posted route, not per booking. No "Scheduled" bucket - a route that hasn't started yet isn't history, it's upcoming. */
    @GetMapping("/drivers/{driverId}/history")
    public ApiResponse<PagedResponse<DriverTripHistoryItem>> historyForDriver(@PathVariable String driverId,
                                                                                @RequestParam(required = false) TripHistoryStatus status,
                                                                                @RequestParam(required = false) String search,
                                                                                @RequestParam(defaultValue = "0") int page,
                                                                                @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(bookingService.getHistoryForDriver(driverId, status, search, pageable));
    }

    /** "View Passenger Manifest" - only the route's own driver can see who's booked on it. */
    @GetMapping("/routes/{routePostId}")
    public ApiResponse<List<BookingSummary>> manifestForRoute(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                @PathVariable String routePostId) {
        return ApiResponse.ok(bookingService.getManifestForRoute(routePostId, principal.getUserId()));
    }

    /** The Receipt screen - only the route's own driver can view it. */
    @GetMapping("/routes/{routePostId}/receipt")
    public ApiResponse<TripReceiptResponse> receipt(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @PathVariable String routePostId) {
        return ApiResponse.ok(bookingService.getTripReceipt(routePostId, principal.getUserId()));
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

    /** Rider backs out of the booking flow before paying - releases the seat immediately instead
     * of leaving it held until SeatHoldExpiryJob's own timeout catches it. Only valid while the
     * booking is still HELD; a CONFIRMED booking must go through /cancel instead. */
    @PostMapping("/{bookingId}/release-hold")
    public ApiResponse<Void> releaseHold(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.releaseHold(bookingId, principal.getUserId());
        return ApiResponse.ok("Seat hold released", null);
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

    /** Undoes a Picked Up/No Show mark made in error - the manifest's "No Show x" undo chip. */
    @PostMapping("/{bookingId}/undo-manifest-mark")
    public ApiResponse<Void> undoManifestMark(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.undoManifestMark(bookingId, principal.getUserId());
        return ApiResponse.ok("Manifest mark reset", null);
    }

    /** "Reset all Manifest" - undoes every mark on the route in one call. */
    @PostMapping("/routes/{routePostId}/reset-manifest")
    public ApiResponse<Void> resetManifest(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String routePostId) {
        bookingService.resetManifest(routePostId, principal.getUserId());
        return ApiResponse.ok("Manifest reset", null);
    }

    @PostMapping("/{bookingId}/complete")
    public ApiResponse<Void> complete(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        bookingService.completeTrip(bookingId, principal.getUserId());
        return ApiResponse.ok("Trip completed", null);
    }

    /** "Complete Trip" button - completes every picked-up passenger on the route at once, gated on the whole manifest being resolved first. */
    @PostMapping("/routes/{routePostId}/complete")
    public ApiResponse<List<BookingSummary>> completeRoute(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String routePostId) {
        return ApiResponse.ok(bookingService.completeRoute(routePostId, principal.getUserId()));
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

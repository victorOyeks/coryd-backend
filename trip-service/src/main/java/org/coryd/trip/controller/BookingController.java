package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.BookingDetailsResponse;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.service.BookingService;
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

    @GetMapping("/{bookingId}")
    public ApiResponse<BookingDetailsResponse> details(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.getBookingDetails(bookingId));
    }

    @PostMapping("/routes/{routePostId}")
    public ApiResponse<Booking> reserve(@PathVariable String routePostId, @Valid @RequestBody BookSeatsRequest request) {
        return ApiResponse.ok(bookingService.reserveSeats(routePostId, request));
    }

    @PostMapping("/{bookingId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String bookingId) {
        bookingService.cancelByRider(bookingId);
        return ApiResponse.ok("Booking cancelled", null);
    }

    @PostMapping("/{bookingId}/en-route")
    public ApiResponse<Void> enRoute(@PathVariable String bookingId) {
        bookingService.markEnRouteToPickup(bookingId);
        return ApiResponse.ok("Driver marked en route to pickup", null);
    }

    @PostMapping("/{bookingId}/arrived-at-pickup")
    public ApiResponse<Void> arrivedAtPickup(@PathVariable String bookingId) {
        bookingService.markArrivedAtPickup(bookingId);
        return ApiResponse.ok("Driver marked arrived at pickup", null);
    }

    @PostMapping("/{bookingId}/picked-up")
    public ApiResponse<Void> pickedUp(@PathVariable String bookingId) {
        bookingService.markPickedUp(bookingId);
        return ApiResponse.ok("Rider marked as picked up", null);
    }

    @PostMapping("/{bookingId}/no-show")
    public ApiResponse<Void> noShow(@PathVariable String bookingId) {
        bookingService.markNoShow(bookingId);
        return ApiResponse.ok("Rider marked as no-show", null);
    }

    @PostMapping("/{bookingId}/complete")
    public ApiResponse<Void> complete(@PathVariable String bookingId) {
        bookingService.completeTrip(bookingId);
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

    @PostMapping("/{bookingId}/report-issue")
    public ApiResponse<IssueReport> reportIssue(@PathVariable String bookingId, @Valid @RequestBody IssueReportRequest request) {
        return ApiResponse.ok(bookingService.reportIssue(bookingId, request));
    }
}

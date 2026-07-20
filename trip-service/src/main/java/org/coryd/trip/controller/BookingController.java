package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.service.BookingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/routes/{routePostId}")
    public ApiResponse<Booking> reserve(@PathVariable String routePostId, @Valid @RequestBody BookSeatsRequest request) {
        return ApiResponse.ok(bookingService.reserveSeats(routePostId, request));
    }

    @PostMapping("/{bookingId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String bookingId) {
        bookingService.cancelByRider(bookingId);
        return ApiResponse.ok("Booking cancelled", null);
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

    @PostMapping("/{bookingId}/report-issue")
    public ApiResponse<IssueReport> reportIssue(@PathVariable String bookingId, @Valid @RequestBody IssueReportRequest request) {
        return ApiResponse.ok(bookingService.reportIssue(bookingId, request));
    }
}

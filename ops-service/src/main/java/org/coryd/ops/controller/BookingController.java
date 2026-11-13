package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.dto.CancelBookingRequest;
import org.coryd.ops.service.TripOpsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One seat: cancel it on the rider's behalf, with the fault deciding the refund. */
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final TripOpsService tripOpsService;

    @PostMapping("/{bookingId}/cancel")
    public ApiResponse<JsonNode> cancel(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String bookingId,
                                        @Valid @RequestBody CancelBookingRequest request) {
        return ApiResponse.ok("Booking cancelled", tripOpsService.cancelBooking(bookingId, request, admin));
    }
}

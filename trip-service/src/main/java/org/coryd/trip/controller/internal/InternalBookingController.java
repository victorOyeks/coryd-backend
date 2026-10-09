package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.BookingPaymentView;
import org.coryd.trip.dto.BookingRouteSummary;
import org.coryd.trip.service.BookingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (payment-service's payment initiation and payment histories) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/bookings")
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingService bookingService;

    @GetMapping("/route-summaries")
    public ApiResponse<List<BookingRouteSummary>> routeSummaries(@RequestParam List<String> ids) {
        return ApiResponse.ok(bookingService.getRouteSummaries(ids));
    }

    @GetMapping("/{bookingId}/payment-view")
    public ApiResponse<BookingPaymentView> paymentView(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.getPaymentView(bookingId));
    }
}

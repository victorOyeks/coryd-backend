package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.BookingPaymentView;
import org.coryd.trip.service.BookingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (payment-service's payment initiation) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/bookings")
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingService bookingService;

    @GetMapping("/{bookingId}/payment-view")
    public ApiResponse<BookingPaymentView> paymentView(@PathVariable String bookingId) {
        return ApiResponse.ok(bookingService.getPaymentView(bookingId));
    }
}

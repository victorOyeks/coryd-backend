package org.coryd.payment.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.CancellationRefund;
import org.coryd.payment.dto.CancellationRefundQuery;
import org.coryd.payment.dto.OpsBookingPayment;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.OpsBookingPaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ops-service's rider history and receipts - service-to-service only, behind InternalApiKeyFilter. Up to 100 bookings a call. */
@Hidden
@RestController
@RequestMapping("/internal/ops/payments")
@RequiredArgsConstructor
public class InternalOpsPaymentController {

    private final OpsBookingPaymentService opsBookingPaymentService;
    private final EarningsService earningsService;

    @GetMapping("/bookings")
    public ApiResponse<List<OpsBookingPayment>> bookings(@RequestParam List<String> ids) {
        return ApiResponse.ok(opsBookingPaymentService.forBookings(ids));
    }

    /** trip-service asks this just before an ops cancellation goes out, so the console shows what payment-service will refund. */
    @PostMapping("/cancellation-refunds")
    public ApiResponse<List<CancellationRefund>> cancellationRefunds(
            @RequestBody List<CancellationRefundQuery> queries) {
        return ApiResponse.ok(earningsService.cancellationRefunds(queries));
    }
}

package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.BookingEarningsItem;
import org.coryd.payment.dto.CommissionRate;
import org.coryd.payment.dto.DebtEntryItem;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.dto.DriverPayoutItem;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.EarningsTripItem;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.OwedBalanceResponse;
import org.coryd.payment.dto.PeriodEarningsSummary;
import org.coryd.payment.dto.RecordSettlementDebtRequest;
import org.coryd.payment.dto.RepayRequest;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.PaymentService;
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

import java.time.YearMonth;
import java.util.List;

/** A driver's own earnings - ops can read any driver's. */
@RestController
@RequestMapping("/earnings")
@RequiredArgsConstructor
public class EarningsController {

    private final EarningsService earningsService;
    private final PaymentService paymentService;

    @GetMapping("/commission-rate")
    public ApiResponse<CommissionRate> commissionRate() {
        return ApiResponse.ok(earningsService.commissionRate());
    }

    /** Earnings screen header: earned, paid to bank, payout in progress, anything owed. */
    @GetMapping("/drivers/{driverId}/summary")
    public ApiResponse<DriverEarningsSummary> summary(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @PathVariable String driverId) {
        return ApiResponse.ok(earningsService.getSummary(driverId, principal.getUserId(), principal.getActiveRole()));
    }

    /** Period-filtered Gross/Commission/Net card - month (e.g. "2026-08") only applies when period=MONTH. */
    @GetMapping("/drivers/{driverId}/summary/period")
    public ApiResponse<PeriodEarningsSummary> periodSummary(@AuthenticationPrincipal CoRydUserDetails principal,
                                                            @PathVariable String driverId,
                                                            @RequestParam EarningsPeriod period,
                                                            @RequestParam(required = false) String month) {
        return ApiResponse.ok(earningsService.getPeriodSummary(driverId, period, parseMonth(month),
                principal.getUserId(), principal.getActiveRole()));
    }

    /** Completed trips and kept cancellation fees for the period, newest first. */
    @GetMapping("/drivers/{driverId}/trips")
    public ApiResponse<PagedResponse<EarningsTripItem>> trips(@AuthenticationPrincipal CoRydUserDetails principal,
                                                              @PathVariable String driverId,
                                                              @RequestParam EarningsPeriod period,
                                                              @RequestParam(required = false) String month,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(earningsService.getTrips(driverId, period, parseMonth(month), pageable,
                principal.getUserId(), principal.getActiveRole()));
    }

    /** Paystack's weekly payouts to the driver's bank, newest first. */
    @GetMapping("/drivers/{driverId}/payouts")
    public ApiResponse<PagedResponse<DriverPayoutItem>> payouts(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                @PathVariable String driverId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(earningsService.getPayouts(driverId, pageable, principal.getUserId(), principal.getActiveRole()));
    }

    /** Pay back what's owed from refunded trips by card - opens a Paystack checkout for the whole amount. */
    @PostMapping("/drivers/{driverId}/repay")
    public ApiResponse<InitiatePaymentResponse> repay(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @PathVariable String driverId,
                                                      @RequestBody(required = false) RepayRequest request) {
        return ApiResponse.ok(paymentService.initiateRepayment(driverId, principal.getUserId(),
                request == null ? null : request.amount(), request == null ? null : request.email(),
                request == null ? null : request.callbackUrl()));
    }

    /** What the driver owes from refunded trips, whether that blocks posting, and the least to pay to unblock. */
    @GetMapping("/drivers/{driverId}/owed")
    public ApiResponse<OwedBalanceResponse> owed(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId) {
        return ApiResponse.ok(earningsService.owedBalance(driverId, principal.getUserId(), principal.getActiveRole()));
    }

    /** Every change to what's owed - refunded trips, amounts taken from fares, card payments, ops corrections. */
    @GetMapping("/drivers/{driverId}/owed/history")
    public ApiResponse<PagedResponse<DebtEntryItem>> owedHistory(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                 @PathVariable String driverId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(earningsService.owedHistory(driverId, pageable, principal.getUserId(), principal.getActiveRole()));
    }

    /** Ops-only correction on top of the automatic charge every refund makes. */
    @PostMapping("/drivers/{driverId}/settlement-debt")
    public ApiResponse<Void> recordSettlementDebt(@AuthenticationPrincipal CoRydUserDetails principal,
                                                  @PathVariable String driverId,
                                                  @Valid @RequestBody RecordSettlementDebtRequest request) {
        earningsService.recordSettlementDebt(driverId, request.amount(), request.reason(), principal.getActiveRole());
        return ApiResponse.ok("Recorded", null);
    }

    /** Net-earned lookup for a set of bookings, e.g. to zip into a driver's trip history by bookingId. */
    @GetMapping("/transactions/batch")
    public ApiResponse<List<BookingEarningsItem>> transactionsBatch(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                    @RequestParam List<String> bookingIds) {
        return ApiResponse.ok(earningsService.getTransactionsForBookings(bookingIds, principal.getUserId(), principal.getActiveRole()));
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new BadRequestException("month must be in yyyy-MM format, e.g. 2026-08");
        }
    }
}

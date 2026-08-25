package org.coryd.payment.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.BookingEarningsItem;
import org.coryd.payment.dto.CommissionRate;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.service.EarningsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/earnings")
@RequiredArgsConstructor
public class EarningsController {

    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final EarningsService earningsService;
    private final CommissionProperties commissionProperties;

    /** So the client computes gross/commission/net from the real configured rate instead of hardcoding it. */
    @GetMapping("/commission-rate")
    public ApiResponse<CommissionRate> commissionRate() {
        return ApiResponse.ok(new CommissionRate(commissionProperties.getPercentage()));
    }

    @GetMapping("/drivers/{driverId}")
    public ApiResponse<DriverEarningsAccount> account(@PathVariable String driverId) {
        return ApiResponse.ok(earningsAccountRepository.findByDriverId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("No earnings account yet for this driver")));
    }

    @GetMapping("/drivers/{driverId}/transactions")
    public ApiResponse<List<EarningsTransaction>> transactions(@PathVariable String driverId) {
        return ApiResponse.ok(earningsTransactionRepository.findByDriverId(driverId));
    }

    /** Dashboard's lifetime/today earnings tiles. */
    @GetMapping("/drivers/{driverId}/summary")
    public ApiResponse<DriverEarningsSummary> summary(@PathVariable String driverId) {
        return ApiResponse.ok(earningsService.getSummary(driverId));
    }

    /** Net-earned lookup for a specific set of bookings, e.g. to zip into a driver trip-history page by bookingId. */
    @GetMapping("/transactions/batch")
    public ApiResponse<List<BookingEarningsItem>> transactionsBatch(@RequestParam List<String> bookingIds) {
        return ApiResponse.ok(earningsService.getTransactionsForBookings(bookingIds));
    }
}

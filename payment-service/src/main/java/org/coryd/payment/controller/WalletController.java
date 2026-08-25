package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.TopUpRequest;
import org.coryd.payment.dto.WalletBalanceResponse;
import org.coryd.payment.dto.WalletTransactionItem;
import org.coryd.payment.dto.WalletTransactionType;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.WalletService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final EarningsService earningsService;

    @GetMapping("/{userId}/balance")
    public ApiResponse<WalletBalanceResponse> balance(@PathVariable String userId) {
        Wallet wallet = walletService.getOrCreateWallet(userId);
        return ApiResponse.ok(new WalletBalanceResponse(wallet.getAvailableBalance(), wallet.getCurrency()));
    }

    @PostMapping("/{userId}/topup/initiate")
    public ApiResponse<InitiatePaymentResponse> initiateTopUp(@PathVariable String userId, @Valid @RequestBody TopUpRequest request) {
        return ApiResponse.ok(walletService.initiateTopUp(userId, request.amount(), request.email(), request.callbackUrl()));
    }

    @GetMapping("/{userId}/transactions")
    public ApiResponse<PagedResponse<WalletTransaction>> transactions(@PathVariable String userId,
                                                                        @RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(walletService.getTransactions(userId, pageable));
    }

    /** Driver Wallet screen's mixed Trip/Withdrawal feed - type omitted means "All"; month (e.g. "2026-08") only applies when period=MONTH. */
    @GetMapping("/drivers/{driverId}/transactions")
    public ApiResponse<PagedResponse<WalletTransactionItem>> driverTransactions(@PathVariable String driverId,
                                                                                  @RequestParam(required = false) WalletTransactionType type,
                                                                                  @RequestParam EarningsPeriod period,
                                                                                  @RequestParam(required = false) String month,
                                                                                  @RequestParam(defaultValue = "0") int page,
                                                                                  @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(PagedResponse.from(
                earningsService.getTransactionFeed(driverId, type, period, EarningsController.parseMonth(month), pageable)));
    }
}

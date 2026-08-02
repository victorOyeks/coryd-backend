package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.TopUpRequest;
import org.coryd.payment.dto.WalletBalanceResponse;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
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
}

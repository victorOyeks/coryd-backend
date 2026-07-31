package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.TopUpRequest;
import org.coryd.payment.dto.WalletBalanceResponse;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
import org.coryd.payment.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        return ApiResponse.ok(walletService.initiateTopUp(userId, request.amount(), request.email()));
    }

    @GetMapping("/{userId}/transactions")
    public ApiResponse<List<WalletTransaction>> transactions(@PathVariable String userId) {
        return ApiResponse.ok(walletService.getTransactions(userId));
    }
}

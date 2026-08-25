package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.PayoutReceiptResponse;
import org.coryd.payment.dto.WithdrawalRequest;
import org.coryd.payment.entity.Payout;
import org.coryd.payment.service.PayoutService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    /** The Withdraw Funds screen's submit action. */
    @PostMapping("/drivers/{driverId}")
    public ApiResponse<Payout> withdraw(@AuthenticationPrincipal CoRydUserDetails principal,
                                         @PathVariable String driverId, @Valid @RequestBody WithdrawalRequest request) {
        return ApiResponse.ok(payoutService.requestWithdrawal(driverId, principal.getUserId(), request.amount()));
    }

    /** The Withdrawal Receipt screen. */
    @GetMapping("/{payoutId}/receipt")
    public ApiResponse<PayoutReceiptResponse> receipt(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String payoutId) {
        return ApiResponse.ok(payoutService.getReceipt(payoutId, principal.getUserId()));
    }
}

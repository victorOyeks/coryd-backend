package org.coryd.payment.dev;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.WalletBalanceResponse;
import org.coryd.payment.entity.Wallet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dev-only. Registered only when coryd.dev-tools.enabled is true. See DevWalletService for why this sits under /wallets/dev/** rather than /dev/**. */
@RestController
@RequestMapping("/wallets/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class DevWalletController {

    private final DevWalletService devWalletService;

    @PostMapping("/{userId}/fund")
    public ApiResponse<WalletBalanceResponse> fund(@AuthenticationPrincipal CoRydUserDetails principal,
                                                     @PathVariable String userId,
                                                     @Valid @RequestBody DevWalletAdjustmentRequest request) {
        Wallet wallet = devWalletService.fund(userId, principal.getActiveRole(), request.amount(), request.reason());
        return ApiResponse.ok(new WalletBalanceResponse(wallet.getAvailableBalance(), wallet.getCurrency()));
    }

    @PostMapping("/{userId}/deduct")
    public ApiResponse<WalletBalanceResponse> deduct(@AuthenticationPrincipal CoRydUserDetails principal,
                                                       @PathVariable String userId,
                                                       @Valid @RequestBody DevWalletAdjustmentRequest request) {
        Wallet wallet = devWalletService.deduct(userId, principal.getActiveRole(), request.amount(), request.reason());
        return ApiResponse.ok(new WalletBalanceResponse(wallet.getAvailableBalance(), wallet.getCurrency()));
    }
}

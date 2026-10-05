package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.PayoutRecipientRequest;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.service.PayoutRecipientService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PayoutRecipientController {

    private final PayoutRecipientService payoutRecipientService;

    /** Bank picker for the "Add bank account" screen. */
    @GetMapping("/banks")
    public ApiResponse<List<PaystackGatewayClient.BankOption>> banks() {
        return ApiResponse.ok(payoutRecipientService.listBanks());
    }

    /** "Confirm this is you" step before saving. */
    @GetMapping("/payout-recipients/resolve")
    public ApiResponse<PaystackGatewayClient.ResolvedAccount> resolve(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                       @RequestParam String accountNumber, @RequestParam String bankCode) {
        return ApiResponse.ok(payoutRecipientService.resolve(principal.getUserId(), accountNumber, bankCode));
    }

    /** Payout Setup screen's saved-accounts list. */
    @GetMapping("/payout-recipients/drivers/{driverId}")
    public ApiResponse<List<PayoutRecipient>> list(@AuthenticationPrincipal CoRydUserDetails principal,
                                                   @PathVariable String driverId) {
        return ApiResponse.ok(payoutRecipientService.list(driverId, principal.getUserId(), principal.getActiveRole()));
    }

    /** Adds a new saved bank account - ownership-gated, since getting this wrong would mean saving bank details under someone else's account. */
    @PostMapping("/payout-recipients/drivers/{driverId}")
    public ApiResponse<PayoutRecipient> add(@AuthenticationPrincipal CoRydUserDetails principal,
                                             @PathVariable String driverId, @Valid @RequestBody PayoutRecipientRequest request) {
        return ApiResponse.ok(payoutRecipientService.add(driverId, principal.getUserId(), request));
    }

    /** "Select preferred payout account" - switches which saved account future withdrawals go to. */
    @PutMapping("/payout-recipients/drivers/{driverId}/{recipientId}/select")
    public ApiResponse<PayoutRecipient> selectDefault(@AuthenticationPrincipal CoRydUserDetails principal,
                                                       @PathVariable String driverId, @PathVariable String recipientId) {
        return ApiResponse.ok(payoutRecipientService.selectDefault(driverId, principal.getUserId(), recipientId));
    }
}

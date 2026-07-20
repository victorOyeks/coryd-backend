package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.PayoutRecipientRequest;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/payout-recipients")
@RequiredArgsConstructor
public class PayoutRecipientController {

    private final PayoutRecipientRepository payoutRecipientRepository;

    @PostMapping("/drivers/{driverId}")
    public ApiResponse<PayoutRecipient> upsert(@PathVariable String driverId, @Valid @RequestBody PayoutRecipientRequest request) {
        PayoutRecipient recipient = payoutRecipientRepository.findByDriverId(driverId)
                .orElseGet(() -> PayoutRecipient.builder().driverId(driverId).build());
        recipient.setBankName(request.bankName());
        recipient.setAccountNumber(request.accountNumber());
        recipient.setAccountName(request.accountName());
        return ApiResponse.ok(payoutRecipientRepository.save(recipient));
    }
}

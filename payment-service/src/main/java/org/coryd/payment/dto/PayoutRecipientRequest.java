package org.coryd.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record PayoutRecipientRequest(
        @NotBlank String bankName,
        @NotBlank String bankCode,
        @NotBlank String accountNumber
) {
}

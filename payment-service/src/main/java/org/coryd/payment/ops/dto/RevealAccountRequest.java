package org.coryd.payment.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** recipientId null reveals the driver's default account. */
public record RevealAccountRequest(String recipientId,
                                   @NotBlank(message = "a reason is required") @Size(max = 500) String reason) {
}

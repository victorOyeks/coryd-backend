package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cancel one seat. RIDER fault keeps the late-cancellation fee back unless waiveFee; DRIVER and PLATFORM refund in full. */
public record CancelBookingRequest(
        @NotBlank(message = "is required") @Size(max = 500) String reason,
        @NotNull(message = "is required - DRIVER, RIDER or PLATFORM") CancellationFault fault,
        Boolean waiveFee
) {
}

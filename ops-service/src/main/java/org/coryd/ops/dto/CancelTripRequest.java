package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cancel a whole trip. fault DRIVER or PLATFORM - every paid rider is refunded in full either way. */
public record CancelTripRequest(
        @NotBlank(message = "is required") @Size(max = 500) String reason,
        @NotNull(message = "is required - DRIVER or PLATFORM") CancellationFault fault
) {
}

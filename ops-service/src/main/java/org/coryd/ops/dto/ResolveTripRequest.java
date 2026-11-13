package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Resolve a stuck trip. COMPLETED: riders aboard are settled, those never picked up refunded (or no-shows with
 * unboardedBookings NO_SHOW). CANCELLED: every paid rider refunded; fault DRIVER or PLATFORM (default PLATFORM).
 */
public record ResolveTripRequest(
        @NotNull(message = "is required - COMPLETED or CANCELLED") StuckTripOutcome outcome,
        @NotBlank(message = "is required") @Size(max = 500) String reason,
        CancellationFault fault,
        UnboardedOutcome unboardedBookings
) {
}

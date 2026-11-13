package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.service.BookingService;

/**
 * ops-service -> trip-service: resolving a stuck trip. COMPLETED settles it as having happened (unboarded riders
 * refunded or no-shows as unboardedBookings says, default REFUND); CANCELLED refunds every paid rider, with fault
 * DRIVER or PLATFORM (default PLATFORM).
 */
public record OpsResolveRequest(
        @NotNull(message = "is required - COMPLETED or CANCELLED") Outcome outcome,
        @NotBlank(message = "is required") @Size(max = 500) String reason,
        CancellationFault fault,
        BookingService.UnboardedOutcome unboardedBookings,
        @NotBlank(message = "is required") String actorId,
        @Size(max = 120) String actorName
) {
    public enum Outcome { COMPLETED, CANCELLED }
}

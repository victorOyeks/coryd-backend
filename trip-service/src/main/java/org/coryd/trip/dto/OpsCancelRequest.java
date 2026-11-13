package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.trip.entity.CancellationFault;

/** ops-service -> trip-service: an ops cancellation, signed with the admin who made it. waiveFee only matters for RIDER fault. */
public record OpsCancelRequest(
        @NotBlank(message = "is required") @Size(max = 500) String reason,
        @NotNull(message = "is required - DRIVER, RIDER or PLATFORM") CancellationFault fault,
        Boolean waiveFee,
        @NotBlank(message = "is required") String actorId,
        @Size(max = 120) String actorName
) {
}

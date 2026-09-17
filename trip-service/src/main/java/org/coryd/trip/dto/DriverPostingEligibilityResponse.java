package org.coryd.trip.dto;

import java.time.Instant;

public record DriverPostingEligibilityResponse(
        boolean canPostRoutes,
        boolean documentsApproved,
        boolean identitySubmitted,
        boolean vehicleRegistered,
        String blockedReason,
        String enforcement,
        int gracePeriodDays,
        Instant firstRouteAt,
        Instant uploadDeadline,
        Integer daysRemaining
) {
}

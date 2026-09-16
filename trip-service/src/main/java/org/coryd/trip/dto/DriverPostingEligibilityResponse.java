package org.coryd.trip.dto;

import java.time.Instant;

public record DriverPostingEligibilityResponse(
        boolean canPostRoutes,
        boolean documentsApproved,
        String enforcement,
        int gracePeriodDays,
        Instant firstRouteAt,
        Instant uploadDeadline,
        Integer daysRemaining
) {
}

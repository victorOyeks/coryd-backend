package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverPostingEligibilityResponse(
        boolean canPostRoutes,
        boolean documentsApproved,
        boolean identityOk,
        boolean vehicleOk,
        boolean payoutAccountOk,
        BigDecimal amountOwed,
        String blockedReason,
        String prerequisiteLevel,
        String enforcement,
        int gracePeriodDays,
        Instant firstRouteAt,
        Instant uploadDeadline,
        Integer daysRemaining
) {
}

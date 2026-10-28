package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverPostingEligibilityResponse(
        boolean canPostRoutes,
        boolean documentsApproved,
        boolean identityOk,
        /** Verified as a person: NIN + selfie approved, or NIN confirmed and licence approved. False blocks with IDENTITY_NOT_VERIFIED. */
        boolean identityVerified,
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

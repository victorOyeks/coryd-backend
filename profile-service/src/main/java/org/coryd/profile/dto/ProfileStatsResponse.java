package org.coryd.profile.dto;

import java.time.Instant;

public record ProfileStatsResponse(
        long approvedDrivers,
        long awaitingApproval,
        Instant oldestPendingAt
) {
}

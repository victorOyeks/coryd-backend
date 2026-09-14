package org.coryd.identity.dto;

public record IdentityStatsResponse(
        long newRiders,
        long newDrivers,
        long totalRiders,
        long totalDrivers
) {
}

package org.coryd.ops.dto;

import org.coryd.common.enums.DriverDirectoryStatus;

import org.coryd.common.enums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver's profile in the Ops Console. suspension is null unless the account is suspended. Performance numbers that
 * couldn't be loaded are null.
 */
public record DriverProfileView(
        DriverSummary driver,
        DriverDirectoryStatus status,
        VerificationStatus verificationStatus,
        Suspension suspension,
        DriverDirectoryItem.Vehicle vehicle,
        Performance performance,
        Account account
) {
    public record Suspension(Instant suspendedAt, String reason) {
    }

    public record Performance(Long totalTrips, Double rating, Long ratingCount, BigDecimal totalEarnings, BigDecimal grossEarnings,
                              Integer reliabilityPercent, Long reportsFiled) {
    }

    public record Account(String ref, Instant joinedAt) {
    }
}

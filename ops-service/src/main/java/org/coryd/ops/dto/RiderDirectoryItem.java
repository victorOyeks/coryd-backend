package org.coryd.ops.dto;

import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.enums.RiderIdentityStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the Riders Directory. rider.ref is "R-" plus the first six characters of their id. reliabilityPercent (the
 * design's Retention column) is null until they've completed, cancelled or missed a paid seat. totalSpent is the fares
 * of completed trips. Numbers trip-service hasn't sent yet are null. identityStatus is NOT_STARTED until they submit an
 * identity (NIN) check, then the identity screen's tab; lastTripAt is when their latest completed trip ended (null if none).
 */
public record RiderDirectoryItem(
        DriverSummary rider,
        Long trips,
        Integer reliabilityPercent,
        Double rating,
        Long ratingCount,
        BigDecimal totalSpent,
        Long reportsFiled,
        RiderDirectoryStatus status,
        Instant joinedAt,
        RiderIdentityStatus identityStatus,
        Instant lastTripAt
) {
}

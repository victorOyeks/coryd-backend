package org.coryd.profile.dto;

import org.coryd.common.enums.RiderDirectoryStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** A rider_index row as ops-service reads it. Trip numbers are null until trip-service has sent them. */
public record RiderIndexView(
        String riderId,
        String firstName,
        String lastName,
        String phone,
        String photoUrl,
        Instant joinedAt,
        RiderDirectoryStatus status,
        Instant suspendedAt,
        String suspensionReason,
        Long trips,
        Double ratingAvg,
        Long ratingCount,
        Integer reliabilityPercent,
        Long reportsFiled,
        BigDecimal totalSpent
) {
}

package org.coryd.profile.dto;

import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** One driver from driver_index, for ops-service's Drivers Directory. Numbers not received yet are null. */
public record DriverIndexView(
        String driverId, String firstName, String lastName, String phone, String photoUrl, Instant joinedAt,
        DriverDirectoryStatus status, VerificationStatus verificationStatus, Instant suspendedAt, String suspensionReason,
        String vehicleMake, String vehicleModel, Integer vehicleYear, String vehicleColor, String plateNumber,
        Long trips, Double ratingAvg, Long ratingCount, Integer reliabilityPercent, Long reportsFiled,
        BigDecimal earningsNet, BigDecimal earningsGross,
        Instant lastTripAt
) {
}

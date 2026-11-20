package org.coryd.ops.dto;

import org.coryd.common.enums.DriverDirectoryStatus;

import org.coryd.common.enums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the Drivers Directory. earnings is the driver's lifetime takings after CoRyd's commission, grossEarnings
 * before it. reliabilityPercent is null until they've completed or cancelled a paid seat. Any number that couldn't be
 * loaded is null, and the row still shows. lastTripAt is when the latest completed ride on their trips ended (null if none).
 * ninPending: every item approved but the identity (NIN) check not - verificationStatus is then PENDING or IN_REVIEW and
 * status PENDING, never VERIFIED; the console shows "NIN pending".
 */
public record DriverDirectoryItem(
        DriverSummary driver,
        Vehicle vehicle,
        Long trips,
        Double rating,
        Long ratingCount,
        BigDecimal earnings,
        BigDecimal grossEarnings,
        Integer reliabilityPercent,
        DriverDirectoryStatus status,
        VerificationStatus verificationStatus,
        Instant joinedAt,
        Instant lastTripAt,
        boolean ninPending
) {
    public record Vehicle(String make, String model, Integer year, String color, String plateNumber) {
    }
}

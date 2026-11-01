package org.coryd.ops.dto;

import org.coryd.common.enums.RiderDirectoryStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** A rider's profile in the Ops Console. suspension is null unless the account is suspended. */
public record RiderProfileView(
        DriverSummary rider,
        RiderDirectoryStatus status,
        DriverProfileView.Suspension suspension,
        Account account,
        Activity activity
) {
    public record Account(String ref, Instant joinedAt) {
    }

    public record Activity(Long totalTrips, Double rating, Long ratingCount, BigDecimal totalSpent, Integer reliabilityPercent,
                           Long reportsFiled) {
    }
}

package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver's lifetime takings as they now stand - sent by payment-service when a fare is credited, and nightly for every
 * driver. Whole values, never deltas; computedAt lets a consumer ignore one older than what it already has.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverEarningsChangedEvent {
    private String driverId;
    private BigDecimal lifetimeGross;
    private BigDecimal lifetimeNet;
    private Instant computedAt;
}

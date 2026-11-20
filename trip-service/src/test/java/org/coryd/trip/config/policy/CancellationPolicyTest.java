package org.coryd.trip.config.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The window edge the app's "free until" shows must be exactly where the fee starts. */
class CancellationPolicyTest {

    private final CancellationPolicy policy = new CancellationPolicy();
    private final Instant departure = Instant.parse("2026-11-20T18:00:00Z");

    @Test
    void theFeeStartsTheWindowBeforeDeparture() {
        assertThat(policy.feeStartsAt(departure)).isEqualTo(Instant.parse("2026-11-20T16:00:00Z"));
    }

    @Test
    void cancellingAtTheEdgeIsStillFreeAndAMomentLaterIsNot() {
        Instant edge = policy.feeStartsAt(departure);
        assertThat(policy.isFeeApplicable(departure, edge)).isFalse();
        assertThat(policy.isFeeApplicable(departure, edge.minusSeconds(1))).isFalse();
        assertThat(policy.isFeeApplicable(departure, edge.plusNanos(1))).isTrue();
        assertThat(policy.isFeeApplicable(departure, departure.plus(Duration.ofMinutes(5)))).isTrue();
    }

    @Test
    void theWindowFollowsTheConfiguredHours() {
        policy.setFreeCancellationWindowHours(5);
        assertThat(policy.feeStartsAt(departure)).isEqualTo(Instant.parse("2026-11-20T13:00:00Z"));
        assertThat(policy.isFeeApplicable(departure, Instant.parse("2026-11-20T13:30:00Z"))).isTrue();
    }

    @Test
    void theFeeIsThePercentageOfTheFareRoundedToKobo() {
        assertThat(policy.applyFee(new BigDecimal("3000.00"), true)).isEqualByComparingTo("750.00");
        assertThat(policy.applyFee(new BigDecimal("3000.00"), false)).isEqualByComparingTo("0");
    }
}

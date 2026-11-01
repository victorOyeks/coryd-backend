package org.coryd.profile.event;

import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.common.kafka.events.RiderStatsChangedEvent;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.profile.service.DriverIndexService;
import org.coryd.profile.service.RiderIndexService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Trip, payment and identity events land on the driver and rider indexes. */
class DriverIndexEventListenerTest {

    private final DriverIndexService index = mock(DriverIndexService.class);
    private final RiderIndexService riders = mock(RiderIndexService.class);
    private final DriverIndexEventListener listener = new DriverIndexEventListener(index, riders);

    @Test
    void eachEventGoesToTheIndex() {
        DriverStatsChangedEvent stats = new DriverStatsChangedEvent("d-1", 3, 4.5, 2, 90, 0, Instant.now());
        DriverEarningsChangedEvent earnings = new DriverEarningsChangedEvent("d-1", BigDecimal.TEN, BigDecimal.ONE, Instant.now());
        Instant at = Instant.parse("2026-10-01T10:00:00Z");

        listener.onStats(stats);
        listener.onEarnings(earnings);
        listener.onSuspended(UserSuspendedEvent.builder().userId("d-1").reason("Unsafe").suspendedAt(at).build());
        listener.onSuspended(UserSuspendedEvent.builder().userId("d-2").reason("Unsafe").build());
        listener.onReinstated(UserReinstatedEvent.builder().userId("d-1").build());

        verify(index).applyStats(stats);
        verify(index).applyEarnings(earnings);
        verify(index).applySuspension("d-1", at, "Unsafe");
        verify(index).applySuspension(eq("d-2"), any(Instant.class), eq("Unsafe"));
        verify(index).applySuspension(eq("d-1"), isNull(), isNull());
        verify(riders).applySuspension("d-1", at, "Unsafe");
        verify(riders).applySuspension(eq("d-2"), any(Instant.class), eq("Unsafe"));
        verify(riders).applySuspension(eq("d-1"), isNull(), isNull());
    }

    @Test
    void riderStatsGoToTheRiderIndex() {
        RiderStatsChangedEvent stats = new RiderStatsChangedEvent("r-1", 5, 4.9, 3, 80, 1, BigDecimal.valueOf(9000), Instant.now());

        listener.onRiderStats(stats);

        verify(riders).applyStats(stats);
    }
}

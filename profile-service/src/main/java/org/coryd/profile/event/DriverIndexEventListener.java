package org.coryd.profile.event;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.profile.service.DriverIndexService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Trip, payment and identity numbers onto driver_index as they change. */
@Component
@RequiredArgsConstructor
public class DriverIndexEventListener {

    private final DriverIndexService driverIndexService;

    @KafkaListener(topics = KafkaTopics.DRIVER_STATS_CHANGED)
    public void onStats(DriverStatsChangedEvent event) {
        driverIndexService.applyStats(event);
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_EARNINGS_CHANGED)
    public void onEarnings(DriverEarningsChangedEvent event) {
        driverIndexService.applyEarnings(event);
    }

    @KafkaListener(topics = KafkaTopics.USER_SUSPENDED)
    public void onSuspended(UserSuspendedEvent event) {
        driverIndexService.applySuspension(event.getUserId(), event.getSuspendedAt() == null ? java.time.Instant.now() : event.getSuspendedAt(),
                event.getReason());
    }

    @KafkaListener(topics = KafkaTopics.USER_REINSTATED)
    public void onReinstated(UserReinstatedEvent event) {
        driverIndexService.applySuspension(event.getUserId(), null, null);
    }
}

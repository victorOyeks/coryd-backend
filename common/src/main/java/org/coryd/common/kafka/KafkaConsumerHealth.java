package org.coryd.common.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports DOWN when any Kafka listener container has stopped running.
 *
 * <p>A service whose consumers have died is not healthy, but nothing here said so: the HTTP port
 * stayed open, so /actuator/health reported UP throughout an outage in which notification-service
 * delivered no OTP at all. For every service in CoRyd that consumes events, doing so is part of
 * the job, so a stopped container belongs in its health where uptime monitoring can see it.
 *
 * <p>Component-scanned from {@code org.coryd.common}, so every service gets this for free. A
 * service with no listeners simply reports UP with {@code running: 0}.
 */
@Component("kafkaConsumers")
@RequiredArgsConstructor
public class KafkaConsumerHealth implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    @Override
    public Health health() {
        List<String> stopped = new ArrayList<>();
        int running = 0;

        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                running++;
            } else {
                stopped.add(container.getListenerId());
            }
        }

        Health.Builder builder = stopped.isEmpty() ? Health.up() : Health.down();
        return builder
                .withDetail("running", running)
                .withDetail("stopped", stopped)
                .build();
    }
}

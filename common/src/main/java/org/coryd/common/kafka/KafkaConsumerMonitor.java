package org.coryd.common.kafka;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects and recovers from a Kafka consumer that has stopped polling.
 *
 * <p>This service went stale in exactly the way nothing here could previously see: the process
 * stayed up, HTTP stayed up, {@code /actuator/health} stayed UP, and the consumers simply stopped
 * pulling records. Signup OTPs piled up on the broker until someone restarted the service by hand,
 * at which point two days of retained notifications all landed at once.
 *
 * <p>Three signals are wired up here. {@link NonResponsiveConsumerEvent} - published because
 * {@link KafkaConsumerDefaults} sets monitorInterval/noPollThreshold - means a consumer has missed its poll
 * deadline; that is the stall, and it gets an automatic container bounce. {@link
 * ContainerStoppedEvent} and {@link ConsumerStoppedEvent} mean a listener has given up entirely,
 * which is otherwise silent. {@link ListenerContainerIdleEvent} is the opposite signal: proof a
 * consumer is alive and polling an empty topic, which is what tells a quiet topic apart from a
 * wedged consumer in the logs.
 *
 * <p>The restart runs on its own thread. The event arrives on the container's monitor thread, and
 * calling {@code stop()} on a container from inside its own callback deadlocks.
 */
@Slf4j
@Component
public class KafkaConsumerMonitor {

    /** How long a restart of the same container is suppressed for, so a persistently unreachable broker produces a bounce every few minutes rather than a tight restart loop. */
    private static final Duration RESTART_COOLDOWN = Duration.ofMinutes(2);

    private final ExecutorService restarter =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "kafka-consumer-restarter"));

    private final Map<String, Instant> lastRestartAttempt = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown;

    @EventListener
    public void onNonResponsiveConsumer(NonResponsiveConsumerEvent event) {
        MessageListenerContainer container = event.getContainer(MessageListenerContainer.class);
        String id = container == null ? String.valueOf(event.getListenerId()) : container.getListenerId();

        log.error("Kafka consumer [{}] has not polled for {}ms (partitions={}) - notifications on "
                        + "these topics are NOT being delivered. Attempting automatic restart.",
                id, event.getTimeSinceLastPoll(), event.getTopicPartitions());

        if (container == null) {
            return;
        }

        Instant now = Instant.now();
        Instant previous = lastRestartAttempt.get(id);
        if (previous != null && Duration.between(previous, now).compareTo(RESTART_COOLDOWN) < 0) {
            log.warn("Kafka consumer [{}] is still unresponsive but was restarted {}s ago - waiting "
                            + "out the cooldown rather than restart-looping.",
                    id, Duration.between(previous, now).toSeconds());
            return;
        }
        lastRestartAttempt.put(id, now);

        restarter.submit(() -> {
            try {
                log.warn("Restarting Kafka listener container [{}]", id);
                container.stop();
                container.start();
                log.warn("Kafka listener container [{}] restarted - running={}", id, container.isRunning());
            } catch (Exception e) {
                log.error("Failed to restart Kafka listener container [{}]: {}", id, e.getMessage(), e);
            }
        });
    }
    /**
     * A container that stops on its own is a silent outage - every notification on its topics is
     * dropped from here on. Suppressed during shutdown, where a stop is expected: an ERROR on
     * every routine deploy would train everyone to ignore the one that matters.
     */
    @EventListener
    public void onContainerStopped(ContainerStoppedEvent event) {
        if (shuttingDown) {
            return;
        }
        log.error("Kafka listener container stopped outside of shutdown: {} - no further "
                + "notifications will be delivered on its topics until it is restarted", event.getSource());
    }

    @EventListener
    public void onConsumerStopped(ConsumerStoppedEvent event) {
        if (shuttingDown) {
            return;
        }
        log.warn("Kafka consumer stopped (reason={}) for {}", event.getReason(), event.getSource());
    }

    /** Idle means healthy-but-quiet: the consumer polled, there was simply nothing to read. */
    @EventListener
    public void onIdle(ListenerContainerIdleEvent event) {
        log.debug("Kafka consumer [{}] idle for {}ms", event.getListenerId(), event.getIdleTime());
    }

    /**
     * Spring publishes this before it stops any lifecycle bean, so the flag is always set before
     * the containers themselves stop - which is what makes the suppression above reliable rather
     * than a race.
     */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        shuttingDown = true;
    }

    @PreDestroy
    void shutdown() {
        restarter.shutdownNow();
    }
}

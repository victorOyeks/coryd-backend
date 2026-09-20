package org.coryd.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The consumer settings every CoRyd service that reads from Kafka should share.
 *
 * <p>Written after notification-service silently stopped consuming: signup OTPs were produced
 * fine and simply never picked up, the process stayed up, {@code /actuator/health} stayed UP, and
 * the only fix anyone had was a manual restart - which then delivered two days of retained
 * notifications in one burst. Every consumer in this codebase had the same bare configuration and
 * so the same blind spot, which is why this lives in common rather than in one service.
 *
 * <p>Pair with {@link KafkaConsumerMonitor} (acts on the stall events configured here) and
 * {@link KafkaConsumerHealth} (surfaces a stopped container in actuator). Both are component-scanned
 * from {@code org.coryd.common}, so every service picks them up automatically.
 */
public final class KafkaConsumerDefaults {

    /** The only package Kafka payloads may deserialize into - keeps a stray message off the classpath-gadget path. */
    public static final String TRUSTED_PACKAGES = "org.coryd.common.kafka.events";

    /**
     * The timing budget the next four values form, in order, is the whole point of this class:
     *
     * <pre>
     *   worst-case time to process one batch   ~150s  (MAX_POLL_RECORDS x ~15s, the slowest a
     *                                                  single handler can now be, since every
     *                                                  outbound call has a timeout)
     *   stall is declared and container bounced  180s  (POLL_TIMEOUT_MS x NO_POLL_THRESHOLD)
     *   broker evicts us from the group          300s  (MAX_POLL_INTERVAL_MS)
     * </pre>
     *
     * <p>Each step has to sit above the one before it. Too tight and ordinary slowness looks like a
     * stall, so the monitor restart-loops a container that was working; too loose and the broker
     * kicks the consumer out before anything notices it stopped polling. Note that Spring's
     * non-responsive check is a <em>ratio</em> - {@code timeSinceLastPoll / pollTimeout >
     * noPollThreshold} - so the default threshold of 3.0 against the default 5s poll timeout would
     * fire after just 15 seconds. Hence the explicit poll timeout.
     */
    public static final int MAX_POLL_RECORDS = 10;
    public static final long POLL_TIMEOUT_MS = 30_000L;
    public static final float NO_POLL_THRESHOLD = 6.0f;
    public static final int MAX_POLL_INTERVAL_MS = 300_000;

    /** Heartbeats run on their own thread, so this only catches a dead process; MAX_POLL_INTERVAL_MS is what catches a live process that stopped polling. */
    public static final int SESSION_TIMEOUT_MS = 45_000;
    public static final int HEARTBEAT_INTERVAL_MS = 3_000;

    /** How often the container checks whether its consumer is still polling. */
    public static final int MONITOR_INTERVAL_SECONDS = 30;

    /** Emits a "still alive, nothing to read" event per consumer, which is what tells a quiet topic apart from a wedged one in the logs. */
    public static final long IDLE_EVENT_INTERVAL_MS = 60_000L;

    private KafkaConsumerDefaults() {
    }

    /**
     * Base consumer properties. The caller adds {@code group.id} - it is the one setting that is
     * genuinely per-service (and, in notification-service, per concern).
     */
    public static Map<String, Object> consumerConfig(String bootstrapServers, String autoOffsetReset) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Deserialization failures are thrown from inside poll(), before any error handler has an
        // offset to seek past - a single malformed record would otherwise re-poll the same offset
        // forever and permanently wedge that partition, with no way out but a manual restart that
        // hits the same record again. ErrorHandlingDeserializer catches it and hands the listener a
        // failed record the error handler can actually skip.
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, TRUSTED_PACKAGES);

        // Cooperative rebalancing: a member joining or leaving no longer revokes every other
        // member's partitions. Under the previous (eager) assignor, one flapping consumer stopped
        // the entire group for the duration of the rebalance - which on a shared broker means an
        // unrelated deploy could stop this service consuming. The single most valuable line here.
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                List.of(CooperativeStickyAssignor.class.getName()));

        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, HEARTBEAT_INTERVAL_MS);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        return config;
    }

    /** Applies the error handling and stall detection described above to a listener container factory. */
    public static void harden(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        // Retry a failing record three times over ~6s, then log it and move on. Blocking a
        // partition indefinitely on one bad record costs every later message on it; for these
        // events that trade is not worth making.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000L, 3L)));

        // Makes the container publish NonResponsiveConsumerEvent, which KafkaConsumerMonitor turns
        // into a loud log line and an automatic bounce.
        factory.getContainerProperties().setPollTimeout(POLL_TIMEOUT_MS);
        factory.getContainerProperties().setNoPollThreshold(NO_POLL_THRESHOLD);
        factory.getContainerProperties().setMonitorInterval(MONITOR_INTERVAL_SECONDS);
        factory.getContainerProperties().setIdleEventInterval(IDLE_EVENT_INTERVAL_MS);
    }
}

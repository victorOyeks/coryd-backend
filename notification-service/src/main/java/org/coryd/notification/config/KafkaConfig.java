package org.coryd.notification.config;

import org.coryd.common.kafka.KafkaConsumerDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    /**
     * One group per concern, not one group for all sixteen listeners.
     *
     * <p>Kafka rebalances a consumer group as a unit. With every {@code @KafkaListener} in this
     * service sharing a single group id, one member that failed to rejoin - a broker blip on the
     * shared marketrun-ng cluster, an unrelated deploy, a dropped connection - left the coordinator
     * stuck in PreparingRebalance and <em>every</em> listener stopped consuming, signup OTPs
     * included. Splitting the group means a wedged trip-notification consumer can no longer take
     * OTP delivery down with it. (The cooperative assignor in {@link KafkaConsumerDefaults}
     * addresses the same failure from the other direction; these are complementary, not redundant.)
     *
     * <p>All four must stay prefixed "coryd-" so they never collide with marketrun-ng's groups on
     * the shared broker. Kept as Java constants because {@code @KafkaListener} needs a
     * compile-time value.
     */
    public static final String GROUP_ID_OTP = "coryd-notification-service-otp";

    /** Booking, trip, driver and payment notifications - everything on the live-trip path. */
    public static final String GROUP_ID_TRIP = "coryd-notification-service-trip";

    /** Verification outcomes, document expiry and other back-office notifications. */
    public static final String GROUP_ID_ADMIN = "coryd-notification-service-admin";

    /**
     * SOS and trip-sharing. Isolated from the trip group on purpose: an emergency alert must not
     * be able to queue behind - or be stalled by - routine booking notifications.
     */
    public static final String GROUP_ID_SAFETY = "coryd-notification-service-safety";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.auto-offset-reset:latest}")
    private String autoOffsetReset;

    /**
     * No group.id here - every {@code @KafkaListener} in this service names one of the four above,
     * and the container overrides the factory's value with it. Everything else about how these
     * consumers behave under failure lives in {@link KafkaConsumerDefaults}, shared with the other
     * services rather than copied into each.
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = KafkaConsumerDefaults.consumerConfig(bootstrapServers, autoOffsetReset);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        KafkaConsumerDefaults.harden(factory);
        return factory;
    }
}

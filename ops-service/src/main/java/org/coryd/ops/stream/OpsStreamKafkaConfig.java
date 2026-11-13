package org.coryd.ops.stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.coryd.common.kafka.KafkaConsumerDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;
import java.util.UUID;

/**
 * The live stream's Kafka consumer. Its group is this instance's alone ("coryd-ops-stream-…" + a random suffix), so with
 * two ops-service instances each one gets every event for its own consoles, rather than Kafka splitting them between the
 * two. Starts at the latest offset: a console that wasn't connected reloads its lists rather than replaying history.
 */
@Configuration
@EnableKafka
@EnableScheduling
public class OpsStreamKafkaConfig {

    public static final String FACTORY = "opsStreamListenerFactory";

    /** This instance's own consumer group: the prefix plus a random suffix (OpsStreamGroupJanitor deletes it on shutdown). */
    public record StreamGroup(String prefix, String id) {
    }

    @Bean
    public StreamGroup opsStreamGroup(@Value("${ops.stream.group-prefix:coryd-ops-stream}") String groupPrefix) {
        return new StreamGroup(groupPrefix + "-", groupPrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, Object> opsStreamListenerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers, StreamGroup streamGroup) {
        Map<String, Object> config = KafkaConsumerDefaults.consumerConfig(bootstrapServers, "latest");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, streamGroup.id());
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        KafkaConsumerDefaults.harden(factory);
        return factory;
    }
}

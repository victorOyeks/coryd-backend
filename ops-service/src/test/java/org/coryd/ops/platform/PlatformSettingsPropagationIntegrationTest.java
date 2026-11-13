package org.coryd.ops.platform;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over a real broker: a setting trip-service publishes reaches another service's PlatformSettings within seconds (here
 * ops-service, with the feed switched on - the same common listener every service runs), a newer record wins, a reset
 * falls back to the property; and the system health screen reads a CoRyd consumer group's lag.
 */
@SpringBootTest(properties = {
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "coryd.platform-settings.kafka.enabled=true"
})
@Testcontainers
class PlatformSettingsPropagationIntegrationTest {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired PlatformSettings settings;
    @Autowired KafkaLagReader lagReader;
    @Autowired org.coryd.ops.stream.OpsStreamGroupJanitor janitor;
    @Autowired org.coryd.ops.stream.OpsStreamKafkaConfig.StreamGroup streamGroup;

    @Test
    void aPublishedSettingReachesAnotherServiceWithinSeconds() throws Exception {
        KafkaTemplate<String, Object> producer = producer();
        assertThat(settings.decimal(PlatformSetting.COMMISSION_PERCENT, new BigDecimal("5"))).isEqualByComparingTo("5");

        producer.send(KafkaTopics.PLATFORM_SETTINGS, "commission.percent", event("7.5", 1)).get();
        awaitValue(new BigDecimal("7.5"));

        producer.send(KafkaTopics.PLATFORM_SETTINGS, "commission.percent", event("6", 0)).get();
        producer.send(KafkaTopics.PLATFORM_SETTINGS, "commission.percent", event(null, 2)).get();
        awaitValue(new BigDecimal("5"));
        assertThat(settings.snapshot().get("commission.percent").getVersion()).as("the stale version 0 never won").isEqualTo(2);
    }

    @Test
    void consumerLagIsReadPerCorydGroup() throws Exception {
        String topic = "coryd-lag-probe";
        KafkaTemplate<String, Object> producer = producer();
        for (int i = 0; i < 5; i++) {
            producer.send(topic, "k" + i, Map.of("n", i)).get();
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "coryd-lag-probe-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(2)));
        }
        KafkaLagReader.Lag lag = lagReader.read();
        assertThat(lag.reachable()).isTrue();
        assertThat(lag.groups()).filteredOn(g -> g.group().equals("coryd-lag-probe-group")).singleElement().satisfies(g -> {
            assertThat(g.lag()).isEqualTo(3);
            assertThat(g.lagByTopic()).containsEntry(topic, 3L);
        });
    }

    /**
     * The false DOWN: a live-stream group left behind by an earlier ops-service start (EMPTY, offsets behind) is listed as
     * ephemeral and raises no alert, the janitor deletes it, and this instance's own stream group is left alone.
     */
    @Test
    void anAbandonedLiveStreamGroupIsNeverAnAlertAndIsCleanedUp() throws Exception {
        String topic = "coryd-stream-probe";
        KafkaTemplate<String, Object> producer = producer();
        for (int i = 0; i < 5; i++) {
            producer.send(topic, "k" + i, Map.of("n", i)).get();
        }
        String abandoned = "coryd-ops-stream-dead1234";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, abandoned);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(1)));
        }

        KafkaLagReader.Lag lag = lagReader.read();
        KafkaLagReader.GroupLag group = lag.groups().stream().filter(g -> g.group().equals(abandoned)).findFirst().orElseThrow();
        assertThat(group.ephemeral()).isTrue();
        assertThat(group.members()).isZero();
        assertThat(group.lag()).isEqualTo(4);
        com.fasterxml.jackson.databind.node.ArrayNode alerts = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        SystemHealthService.kafkaAlerts(List.of(group), alerts);
        assertThat(alerts).as("an abandoned stream group is not a stopped consumer").isEmpty();
        SystemHealthService.kafkaAlerts(List.of(new KafkaLagReader.GroupLag("coryd-notification-service-trip", "EMPTY", 0, 4,
                Map.of(topic, 4L), false)), alerts);
        assertThat(alerts).as("a real consumer group that stopped still is").hasSize(1);

        assertThat(janitor.sweep()).contains(abandoned).doesNotContain(streamGroup.id());
        assertThat(lagReader.read().groups()).extracting(KafkaLagReader.GroupLag::group).doesNotContain(abandoned);
    }

    private void awaitValue(BigDecimal expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            BigDecimal now = settings.decimal(PlatformSetting.COMMISSION_PERCENT, new BigDecimal("5"));
            if (now.compareTo(expected) == 0) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(settings.decimal(PlatformSetting.COMMISSION_PERCENT, new BigDecimal("5"))).isEqualByComparingTo(expected);
    }

    private static PlatformSettingChangedEvent event(String value, long version) {
        return PlatformSettingChangedEvent.builder().key("commission.percent").value(value).version(version).changedAt(Instant.now()).build();
    }

    private static KafkaTemplate<String, Object> producer() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

}

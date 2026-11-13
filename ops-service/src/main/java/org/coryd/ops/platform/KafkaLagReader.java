package org.coryd.ops.platform;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Consumer lag per CoRyd consumer group (every group named coryd-*), straight from the broker: committed offset vs the end
 * of each partition. The broker is shared with marketrun-ng, so only CoRyd's groups are read.
 */
@Slf4j
@Component
public class KafkaLagReader implements DisposableBean {

    static final String PREFIX = "coryd-";

    private final String bootstrapServers;
    private final List<String> ephemeralPrefixes;
    private volatile Admin admin;

    public KafkaLagReader(@Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers,
                          @Value("${ops.health.ephemeral-group-prefixes:coryd-ops-stream-}") List<String> ephemeralPrefixes) {
        this.bootstrapServers = bootstrapServers;
        this.ephemeralPrefixes = ephemeralPrefixes == null ? List.of() : ephemeralPrefixes.stream().filter(p -> !p.isBlank()).toList();
    }

    /**
     * ephemeral: a group that belongs to one running process and is thrown away with it - ops-service's live-stream groups
     * (coryd-ops-stream-&lt;random&gt;, one per instance start). Listed, but never alerted on: an old one left EMPTY with
     * lag is not a consumer that stopped (see SystemHealthService).
     */
    public record GroupLag(String group, String state, int members, long lag, Map<String, Long> lagByTopic, boolean ephemeral) {
    }

    boolean ephemeral(String group) {
        return ephemeralPrefixes.stream().anyMatch(group::startsWith);
    }

    public record Lag(boolean reachable, String error, List<GroupLag> groups) {
    }

    public Lag read() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            return new Lag(false, "No Kafka configured", List.of());
        }
        try {
            Admin client = admin();
            List<String> groups = new ArrayList<>();
            for (ConsumerGroupListing listing : client.listConsumerGroups().all().get(5, TimeUnit.SECONDS)) {
                if (listing.groupId().startsWith(PREFIX)) {
                    groups.add(listing.groupId());
                }
            }
            groups.sort(String::compareTo);
            Map<String, ConsumerGroupDescription> descriptions = groups.isEmpty() ? Map.of()
                    : client.describeConsumerGroups(groups).all().get(5, TimeUnit.SECONDS);
            List<GroupLag> out = new ArrayList<>();
            for (String group : groups) {
                Map<TopicPartition, OffsetAndMetadata> committed = client.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
                committed.keySet().forEach(tp -> latest.put(tp, OffsetSpec.latest()));
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = latest.isEmpty() ? Map.of()
                        : client.listOffsets(latest).all().get(5, TimeUnit.SECONDS);
                Map<String, Long> byTopic = new TreeMap<>();
                long total = 0;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                    if (e.getValue() == null || !ends.containsKey(e.getKey())) {
                        continue;
                    }
                    long lag = Math.max(0, ends.get(e.getKey()).offset() - e.getValue().offset());
                    byTopic.merge(e.getKey().topic(), lag, Long::sum);
                    total += lag;
                }
                ConsumerGroupDescription d = descriptions.get(group);
                out.add(new GroupLag(group, d == null ? null : d.state().name(), d == null ? 0 : d.members().size(), total, byTopic,
                        ephemeral(group)));
            }
            return new Lag(true, null, out);
        } catch (Exception e) {
            log.warn("Couldn't read consumer lag: {}", e.getMessage());
            return new Lag(false, e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    private Admin admin() {
        if (admin == null) {
            synchronized (this) {
                if (admin == null) {
                    Map<String, Object> config = new HashMap<>();
                    config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
                    config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8_000);
                    config.put(AdminClientConfig.CLIENT_ID_CONFIG, "coryd-ops-lag-reader");
                    admin = Admin.create(config);
                }
            }
        }
        return admin;
    }

    @Override
    public void destroy() {
        if (admin != null) {
            admin.close();
        }
    }
}

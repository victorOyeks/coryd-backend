package org.coryd.ops.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.common.ConsumerGroupState;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the live stream's consumer groups from piling up on the (shared) broker. Each ops-service start makes a new group
 * (coryd-ops-stream-&lt;random&gt;, see OpsStreamKafkaConfig); the old one used to stay behind, EMPTY, its committed
 * offsets ever further behind - System Health read that as a consumer that had stopped and showed DOWN.
 *
 * <ul>
 *   <li>On shutdown (after the listener containers have stopped) this instance deletes its own group.</li>
 *   <li>At startup and every hour it deletes any other stream group that is EMPTY or DEAD - left by an instance that
 *       crashed. A group with members (another running instance) is never touched; the broker refuses that anyway.</li>
 * </ul>
 * Best effort: a broker that doesn't answer within a few seconds is logged and skipped.
 */
@Slf4j
@Component
public class OpsStreamGroupJanitor implements DisposableBean {

    private static final long TIMEOUT_SECONDS = 4;

    private final String bootstrapServers;
    private final OpsStreamKafkaConfig.StreamGroup own;
    private final boolean enabled;
    private volatile Admin admin;
    /** The broker answered at least once - when it never did, shutdown doesn't wait on it. */
    private volatile boolean reached;

    public OpsStreamGroupJanitor(@Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers,
                                 OpsStreamKafkaConfig.StreamGroup own,
                                 @Value("${ops.stream.group-cleanup:true}") boolean enabled) {
        this.bootstrapServers = bootstrapServers;
        this.own = own;
        this.enabled = enabled && bootstrapServers != null && !bootstrapServers.isBlank();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (enabled) {
            Thread.ofVirtual().name("ops-stream-group-janitor").start(this::sweep);
        }
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public void hourly() {
        if (enabled) {
            sweep();
        }
    }

    /** Deletes every stream group but this instance's own that has no members. Returns the ones deleted. */
    public List<String> sweep() {
        List<String> deleted = new ArrayList<>();
        try {
            List<String> stale = new ArrayList<>();
            var groups = admin().listConsumerGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            reached = true;
            for (ConsumerGroupListing g : groups) {
                Optional<ConsumerGroupState> state = g.state();
                boolean idle = state.isEmpty() || state.get() == ConsumerGroupState.EMPTY || state.get() == ConsumerGroupState.DEAD;
                if (g.groupId().startsWith(own.prefix()) && !g.groupId().equals(own.id()) && idle) {
                    stale.add(g.groupId());
                }
            }
            for (String group : stale) {
                try {
                    admin().deleteConsumerGroups(List.of(group)).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    deleted.add(group);
                } catch (Exception e) {
                    log.debug("Stream group {} not deleted (probably in use again): {}", group, e.getMessage());
                }
            }
            if (!deleted.isEmpty()) {
                log.info("Deleted {} stale live-stream consumer group(s): {}", deleted.size(), deleted);
            }
        } catch (Exception e) {
            log.warn("Couldn't clean up live-stream consumer groups: {}", e.getMessage());
        }
        return deleted;
    }

    /** Runs after the listener containers stopped (they're lifecycle beans), so the group is EMPTY by now. */
    @Override
    public void destroy() {
        if (enabled && reached) {
            try {
                admin().deleteConsumerGroups(List.of(own.id())).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Deleted this instance's live-stream consumer group {}", own.id());
            } catch (Exception e) {
                log.info("Live-stream consumer group {} not deleted on shutdown ({}) - the next start's sweep will", own.id(),
                        e.getMessage());
            }
        }
        if (admin != null) {
            admin.close(Duration.ofSeconds(2));
        }
    }

    private Admin admin() {
        if (admin == null) {
            synchronized (this) {
                if (admin == null) {
                    Map<String, Object> config = new HashMap<>();
                    config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
                    config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 4_000);
                    config.put(AdminClientConfig.CLIENT_ID_CONFIG, "coryd-ops-stream-janitor");
                    admin = Admin.create(config);
                }
            }
        }
        return admin;
    }
}

package org.coryd.common.security.revocation;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loads the full picture from identity-service once the service is up, retrying every 30 seconds until it works, then
 * re-reads it every few minutes. This is the safety net under the Kafka feed: a service that started while Kafka was down,
 * or a suspension made before the topic existed, is still covered. Runs on its own daemon thread - never blocks startup or
 * a request.
 */
@Slf4j
@Component
public class TokenRevocationBootstrap implements DisposableBean {

    private static final long RETRY_SECONDS = 30;

    private final TokenRevocationRegistry registry;
    private final List<TokenRevocationSource> sources;
    private final long refreshMinutes;
    private final boolean enabled;
    private ScheduledExecutorService executor;
    private volatile boolean loaded;

    public TokenRevocationBootstrap(TokenRevocationRegistry registry, List<TokenRevocationSource> sources,
                                    @Value("${coryd.token-revocation.refresh-minutes:5}") long refreshMinutes,
                                    @Value("${coryd.token-revocation.bootstrap.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.sources = sources;
        this.refreshMinutes = Math.max(1, refreshMinutes);
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled || sources.isEmpty() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "token-revocation-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::loadUntilDone);
    }

    private void loadUntilDone() {
        if (loadOnce()) {
            executor.scheduleWithFixedDelay(this::loadOnce, refreshMinutes, refreshMinutes, TimeUnit.MINUTES);
        } else {
            executor.schedule(this::loadUntilDone, RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** True when every source answered. Public for tests. */
    public boolean loadOnce() {
        boolean ok = true;
        for (TokenRevocationSource source : sources) {
            try {
                List<UserTokensRevokedEvent> states = source.fetchAll();
                states.forEach(registry::apply);
                if (!loaded) {
                    log.info("Token revocations loaded: {} users", states.size());
                }
            } catch (Exception e) {
                ok = false;
                log.warn("Couldn't load token revocations from {} - relying on the Kafka feed until it answers: {}",
                        source.getClass().getSimpleName(), e.getMessage());
            }
        }
        loaded = loaded || ok;
        return ok;
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}

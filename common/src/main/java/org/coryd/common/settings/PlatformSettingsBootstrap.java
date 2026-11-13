package org.coryd.common.settings;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
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
 * Loads every platform setting from trip-service once the service is up, retrying every 30 seconds until it works, then
 * re-reads them every few minutes - the safety net under the Kafka feed, the same way TokenRevocationBootstrap covers
 * session revocations. Its own daemon thread: never blocks startup or a request.
 */
@Slf4j
@Component
public class PlatformSettingsBootstrap implements DisposableBean {

    private static final long RETRY_SECONDS = 30;

    private final PlatformSettings settings;
    private final List<PlatformSettingsSource> sources;
    private final long refreshMinutes;
    private final boolean enabled;
    private ScheduledExecutorService executor;
    private volatile boolean loaded;

    public PlatformSettingsBootstrap(PlatformSettings settings, List<PlatformSettingsSource> sources,
                                     @Value("${coryd.platform-settings.refresh-minutes:5}") long refreshMinutes,
                                     @Value("${coryd.platform-settings.bootstrap.enabled:true}") boolean enabled) {
        this.settings = settings;
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
            Thread thread = new Thread(r, "platform-settings-bootstrap");
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
        for (PlatformSettingsSource source : sources) {
            try {
                List<PlatformSettingChangedEvent> states = source.fetchAll();
                states.forEach(settings::apply);
                if (!loaded) {
                    log.info("Platform settings loaded: {} set by ops", states.size());
                }
            } catch (Exception e) {
                ok = false;
                log.warn("Couldn't load platform settings from {} - using the Kafka feed and service defaults until it answers: {}",
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

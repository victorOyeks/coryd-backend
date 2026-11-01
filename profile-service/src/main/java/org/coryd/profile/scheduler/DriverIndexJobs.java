package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.service.DriverIndexService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * driver_index upkeep: on startup, index any driver who isn't yet (a fresh deploy, or one missed); nightly, rebuild every
 * row from the driver records and identity - so anything an event or a save missed is right again by morning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverIndexJobs {

    static final int BATCH = 200;

    private final DriverIndexRepository driverIndexRepository;
    private final DriverIndexService driverIndexService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread.ofVirtual().name("driver-index-backfill").start(() -> {
            try {
                int done = backfill();
                if (done > 0) {
                    log.info("Driver index: indexed {} driver(s) that weren't yet", done);
                }
            } catch (Exception e) {
                log.warn("Driver index backfill failed: {}", e.getMessage());
            }
        });
    }

    /** Only drivers missing from the index - always from the first page, as each batch indexed leaves the "missing" set. */
    public int backfill() {
        int done = 0;
        while (true) {
            Slice<String> missing = driverIndexRepository.findDriversMissingFromIndex(PageRequest.of(0, BATCH));
            if (missing.getContent().isEmpty()) {
                return done;
            }
            int indexed = driverIndexService.refreshAll(missing.getContent());
            done += indexed;
            if (indexed == 0) {
                return done;
            }
        }
    }

    @Scheduled(cron = "${driver-index.rebuild-cron:0 20 2 * * *}", zone = "Africa/Lagos")
    public int rebuildAll() {
        int done = pages(driverIndexRepository::findAllDriverIds);
        log.info("Driver index: rebuilt {} driver(s)", done);
        return done;
    }

    private int pages(Function<Pageable, Slice<String>> query) {
        int done = 0;
        Slice<String> page = query.apply(PageRequest.of(0, BATCH));
        while (true) {
            done += driverIndexService.refreshAll(page.getContent());
            if (!page.hasNext()) {
                return done;
            }
            page = query.apply(page.nextPageable());
        }
    }
}

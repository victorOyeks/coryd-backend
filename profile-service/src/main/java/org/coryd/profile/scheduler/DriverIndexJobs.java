package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.service.IndexRefresh;
import org.coryd.profile.service.DriverIndexService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /** Only drivers missing from the index, in id order. A driver whose row fails is logged, counted and passed over; the
     * next start retries. */
    public int backfill() {
        IndexRefresh total = IndexRefresh.NONE;
        String after = "";
        while (true) {
            List<String> missing = driverIndexRepository.findDriversMissingFromIndex(after, PageRequest.of(0, BATCH)).getContent();
            if (missing.isEmpty()) {
                if (total.failed() > 0) {
                    log.warn("Driver index backfill: {} driver(s) could not be indexed - the nightly rebuild and the next start retry them",
                            total.failed());
                }
                return total.indexed();
            }
            total = total.plus(batch(missing));
            after = missing.get(missing.size() - 1);
        }
    }

    /** Rebuilds every driver's row. A row that fails - or a whole batch, if its lookups fail - doesn't stop the rest. */
    @Scheduled(cron = "${driver-index.rebuild-cron:0 20 2 * * *}", zone = "Africa/Lagos")
    public int rebuildAll() {
        IndexRefresh total = pages(driverIndexRepository::findAllDriverIds);
        log.info("Driver index: rebuilt {} driver(s), {} failed", total.indexed(), total.failed());
        return total.indexed();
    }

    private IndexRefresh pages(Function<Pageable, Slice<String>> query) {
        IndexRefresh total = IndexRefresh.NONE;
        Slice<String> page = query.apply(PageRequest.of(0, BATCH));
        while (true) {
            total = total.plus(batch(page.getContent()));
            if (!page.hasNext()) {
                return total;
            }
            page = query.apply(page.nextPageable());
        }
    }

    private IndexRefresh batch(List<String> driverIds) {
        if (driverIds.isEmpty()) {
            return IndexRefresh.NONE;
        }
        try {
            return driverIndexService.refreshAll(driverIds);
        } catch (RuntimeException e) {
            log.warn("Driver index: a batch of {} driver(s) from {} failed - carrying on with the next: {}",
                    driverIds.size(), driverIds.get(0), e.toString());
            return new IndexRefresh(0, driverIds.size());
        }
    }
}

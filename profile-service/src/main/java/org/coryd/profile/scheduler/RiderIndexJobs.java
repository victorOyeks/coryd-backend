package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.service.IndexRefresh;
import org.coryd.profile.service.RiderIndexService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** rider_index upkeep: on startup, index any rider who isn't yet; nightly, rebuild every row from the profile and identity. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiderIndexJobs {

    static final int BATCH = 200;

    private final RiderIndexRepository riderIndexRepository;
    private final RiderIndexService riderIndexService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread.ofVirtual().name("rider-index-backfill").start(() -> {
            try {
                int done = backfill();
                if (done > 0) {
                    log.info("Rider index: indexed {} rider(s) that weren't yet", done);
                }
            } catch (Exception e) {
                log.warn("Rider index backfill failed: {}", e.getMessage());
            }
        });
    }

    /** Indexes riders who aren't yet. A rider whose row fails is logged, counted and passed over; the next start retries. */
    public int backfill() {
        IndexRefresh total = IndexRefresh.NONE;
        String after = "";
        while (true) {
            List<String> missing = riderIndexRepository.findRidersMissingFromIndex(after, PageRequest.of(0, BATCH)).getContent();
            if (missing.isEmpty()) {
                if (total.failed() > 0) {
                    log.warn("Rider index backfill: {} rider(s) could not be indexed - the nightly rebuild and the next start retry them",
                            total.failed());
                }
                return total.indexed();
            }
            total = total.plus(batch(missing));
            after = missing.get(missing.size() - 1);
        }
    }

    /** Rebuilds every rider's row. A row that fails - or a whole batch, if its lookups fail - doesn't stop the rest. */
    @Scheduled(cron = "${rider-index.rebuild-cron:0 25 2 * * *}", zone = "Africa/Lagos")
    public int rebuildAll() {
        IndexRefresh total = IndexRefresh.NONE;
        Slice<String> page = riderIndexRepository.findAllRiderIds(PageRequest.of(0, BATCH));
        while (true) {
            total = total.plus(batch(page.getContent()));
            if (!page.hasNext()) {
                break;
            }
            page = riderIndexRepository.findAllRiderIds(page.nextPageable());
        }
        log.info("Rider index: rebuilt {} rider(s), {} failed", total.indexed(), total.failed());
        return total.indexed();
    }

    private IndexRefresh batch(List<String> riderIds) {
        if (riderIds.isEmpty()) {
            return IndexRefresh.NONE;
        }
        try {
            return riderIndexService.refreshAll(riderIds);
        } catch (RuntimeException e) {
            log.warn("Rider index: a batch of {} rider(s) from {} failed - carrying on with the next: {}",
                    riderIds.size(), riderIds.get(0), e.toString());
            return new IndexRefresh(0, riderIds.size());
        }
    }
}

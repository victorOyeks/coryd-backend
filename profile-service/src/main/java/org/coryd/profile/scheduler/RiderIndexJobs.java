package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.repository.RiderIndexRepository;
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

    public int backfill() {
        int done = 0;
        String after = "";
        while (true) {
            List<String> missing = riderIndexRepository.findRidersMissingFromIndex(after, PageRequest.of(0, BATCH)).getContent();
            if (missing.isEmpty()) {
                return done;
            }
            done += riderIndexService.refreshAll(missing);
            after = missing.get(missing.size() - 1);
        }
    }

    @Scheduled(cron = "${rider-index.rebuild-cron:0 25 2 * * *}", zone = "Africa/Lagos")
    public int rebuildAll() {
        int done = 0;
        Slice<String> page = riderIndexRepository.findAllRiderIds(PageRequest.of(0, BATCH));
        while (true) {
            done += riderIndexService.refreshAll(page.getContent());
            if (!page.hasNext()) {
                break;
            }
            page = riderIndexRepository.findAllRiderIds(page.nextPageable());
        }
        log.info("Rider index: rebuilt {} rider(s)", done);
        return done;
    }
}

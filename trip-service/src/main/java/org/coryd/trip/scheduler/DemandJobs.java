package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.service.DemandService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The evening demand push to drivers, and clearing out searches older than the retention window. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandJobs {

    private final DemandService demandService;

    /** 7 PM Lagos - when drivers plan tomorrow's trip. */
    @Scheduled(cron = "${trip.demand.alert-cron:0 0 19 * * *}", zone = "Africa/Lagos")
    public void eveningAlerts() {
        try {
            demandService.sendEveningAlerts();
        } catch (Exception e) {
            log.error("Demand alerts failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Lagos")
    public void deleteOldSearches() {
        int deleted = demandService.deleteOldSearches();
        if (deleted > 0) {
            log.info("Deleted {} rider search(es) past the retention window", deleted);
        }
    }
}

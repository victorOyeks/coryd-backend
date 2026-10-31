package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.service.NearbyAlertService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: tell drivers near waiting riders that they're going their way, and forget stale locations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NearbyAlertJob {

    private final NearbyAlertService nearbyAlertService;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        try {
            nearbyAlertService.sendAlerts();
        } catch (Exception e) {
            log.error("Nearby rider alerts failed: {}", e.getMessage(), e);
        }
        try {
            nearbyAlertService.clearStale();
        } catch (Exception e) {
            log.error("Clearing stale driver locations failed: {}", e.getMessage(), e);
        }
    }
}

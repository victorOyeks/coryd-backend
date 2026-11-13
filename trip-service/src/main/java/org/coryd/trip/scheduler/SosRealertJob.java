package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.service.OpsSosService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 seconds: SOS-queue incidents nobody in ops has acknowledged past a threshold alert ops again (once per threshold). */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosRealertJob {

    private final OpsSosService opsSosService;

    @Scheduled(fixedDelayString = "${trip.sos.realert-check-ms:30000}", initialDelayString = "${trip.sos.realert-check-ms:30000}")
    public void run() {
        try {
            opsSosService.realertUnacknowledged();
        } catch (Exception e) {
            log.error("SOS re-alert check failed: {}", e.getMessage(), e);
        }
    }
}

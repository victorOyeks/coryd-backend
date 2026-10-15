package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.service.TripSafetyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: trips whose driver went quiet, and "Are you OK?" checks nobody answered. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripSafetyJob {

    private final TripSafetyService tripSafetyService;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        try {
            tripSafetyService.checkSignalLost();
        } catch (Exception e) {
            log.error("Signal-lost check failed: {}", e.getMessage(), e);
        }
        try {
            tripSafetyService.escalateUnanswered();
        } catch (Exception e) {
            log.error("Safety escalation failed: {}", e.getMessage(), e);
        }
    }
}

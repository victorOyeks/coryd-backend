package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.service.LiveSearchSessionRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops live-search sessions nobody has refreshed (via a fresh REST search) in a while - a rider
 * who closed the app or navigated off the search screen. */
@Component
@RequiredArgsConstructor
public class LiveSearchSessionCleanupJob {

    private final LiveSearchSessionRegistry registry;

    @Scheduled(fixedDelay = 5 * 60_000)
    public void removeExpiredSessions() {
        registry.removeExpired();
    }
}

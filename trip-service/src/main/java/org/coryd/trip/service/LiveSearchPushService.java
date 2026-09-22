package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.event.RouteSearchRelevantChangeEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Pushes fresh results to every active live-search session whenever a route change could affect
 * one - reuses RouteService.search() itself so a WS push and a fresh REST search can never
 * disagree. AFTER_COMMIT so a change that then rolls back never triggers a push, @Async so
 * re-running every active session's search never adds latency to the booking/posting call that
 * triggered it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSearchPushService {

    private final LiveSearchSessionRegistry registry;
    private final RouteService routeService;
    private final SimpMessagingTemplate messagingTemplate;

    /** fallbackExecution so a publisher that turns out not to be in a transaction still pushes -
     * without it the listener is skipped in silence, which is exactly how route cancellation went
     * unnoticed. */
    @Async("liveSearchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRelevantChange(RouteSearchRelevantChangeEvent event) {
        log.debug("Route {} changed - re-running every live search session", event.routePostId());
        registry.forEachSession(this::pushIfChanged);
    }

    private void pushIfChanged(String sessionId, LiveSearchSessionRegistry.Session session) {
        try {
            List<RideSearchResult> results = routeService.search(session.request());
            // Compared whole, because everything on the card can move without the route set moving:
            // seats taken, fare edited, driver newly verified, note changed.
            if (results.equals(session.lastResults())) {
                log.debug("Live search session {} unchanged at {} result(s) - nothing pushed", sessionId, results.size());
                return;
            }
            registry.updateLastResults(sessionId, results);
            messagingTemplate.convertAndSend("/topic/search-sessions/" + sessionId, results);
            log.info("Pushed {} result(s) to live search session {}", results.size(), sessionId);
        } catch (Exception e) {
            log.warn("Live search push failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}

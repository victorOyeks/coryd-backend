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
import java.util.Set;
import java.util.stream.Collectors;

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

    @Async("liveSearchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRelevantChange(RouteSearchRelevantChangeEvent event) {
        registry.forEachSession(this::pushIfChanged);
    }

    private void pushIfChanged(String sessionId, LiveSearchSessionRegistry.Session session) {
        try {
            List<RideSearchResult> results = routeService.search(session.request());
            Set<String> resultIds = results.stream().map(RideSearchResult::routePostId).collect(Collectors.toSet());
            if (resultIds.equals(session.lastResultIds())) {
                return;
            }
            registry.updateLastResults(sessionId, resultIds);
            messagingTemplate.convertAndSend("/topic/search-sessions/" + sessionId, results);
        } catch (Exception e) {
            log.warn("Live search push failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}

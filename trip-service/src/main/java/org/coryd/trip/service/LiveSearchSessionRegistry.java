package org.coryd.trip.service;

import org.coryd.trip.dto.SearchRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Active live-search subscriptions, in memory only - matches WebSocketConfig's simple broker
 * (single process, no external broker), so a session outliving this instance would be meaningless
 * anyway. A session is created/replaced by every POST /routes/search that carries a sessionId, and
 * read by both WebSocketAuthInterceptor (does this caller own this session?) and LiveSearchPushService
 * (what criteria to re-run, what results the client already has). Expired via a scheduled sweep
 * rather than on WebSocket disconnect - pushing to a topic with no subscribers is a harmless no-op,
 * so there's no correctness reason to wire up a SessionDisconnectEvent listener for this.
 */
@Component
public class LiveSearchSessionRegistry {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    public record Session(String riderId, SearchRequest request, Instant lastSeenAt, Set<String> lastResultIds) {
        Session withLastResultIds(Set<String> ids) {
            return new Session(riderId, request, lastSeenAt, ids);
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Called from every live-search REST call - currentResultIds seeds lastResultIds so the first
     * push only fires once something actually changes beyond what this response already returned. */
    public void registerOrRefresh(String sessionId, String riderId, SearchRequest request, Set<String> currentResultIds) {
        sessions.put(sessionId, new Session(riderId, request, Instant.now(), currentResultIds));
    }

    public Session get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void updateLastResults(String sessionId, Set<String> resultIds) {
        sessions.computeIfPresent(sessionId, (id, session) -> session.withLastResultIds(resultIds));
    }

    public void forEachSession(BiConsumer<String, Session> action) {
        sessions.forEach(action);
    }

    public void removeExpired() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(e -> e.getValue().lastSeenAt().isBefore(cutoff));
    }
}

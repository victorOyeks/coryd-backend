package org.coryd.common.security.revocation;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every user whose sessions were ever ended (suspended, signed out everywhere, deleted) - in memory, in every service.
 * JwtFilter asks it about each request: one map lookup, no I/O. Fed by the compacted user-tokens-revoked topic
 * (TokenRevocationKafkaListener, live) and by a snapshot from identity-service (TokenRevocationBootstrap, at startup and
 * every few minutes), so it holds even if Kafka lags or was down at startup.
 *
 * <p>Fails open only for users it has never heard of - they were never revoked, which is almost everyone.
 *
 * <p>Merge rules, so the two feeds can arrive in any order: the cut-off only ever moves forward (a reinstate never
 * restores an old token); "suspended" follows whichever state is newer by {@code stateAt}.
 */
@Slf4j
@Component
public class TokenRevocationRegistry {

    public static final String SUSPENDED_MESSAGE = "This account has been suspended. Contact CoRyd support.";
    public static final String SIGNED_OUT_MESSAGE = "Your session has ended. Please sign in again.";

    private record State(long validAfterMs, boolean suspended, long stateAtMs) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public void apply(UserTokensRevokedEvent event) {
        if (event == null || event.getUserId() == null) {
            return;
        }
        apply(event.getUserId(), event.getTokensValidAfter(), event.isSuspended(), event.getStateAt());
    }

    public void apply(String userId, Instant tokensValidAfter, boolean suspended, Instant stateAt) {
        long validAfter = tokensValidAfter == null ? 0L : tokensValidAfter.toEpochMilli();
        long at = stateAt == null ? 0L : stateAt.toEpochMilli();
        states.merge(userId, new State(validAfter, suspended, at), (old, incoming) -> {
            boolean newer = incoming.stateAtMs() >= old.stateAtMs();
            return new State(Math.max(old.validAfterMs(), incoming.validAfterMs()),
                    newer ? incoming.suspended() : old.suspended(),
                    Math.max(old.stateAtMs(), incoming.stateAtMs()));
        });
    }

    /**
     * Null when a token for this user, issued at issuedAtMs, may be used; otherwise the message to send back with the 401.
     * A suspended account's tokens are all refused, whenever they were issued.
     */
    public String refusal(String userId, long issuedAtMs) {
        if (userId == null) {
            return null;
        }
        State state = states.get(userId);
        if (state == null) {
            return null;
        }
        if (state.suspended()) {
            return SUSPENDED_MESSAGE;
        }
        return issuedAtMs < state.validAfterMs() ? SIGNED_OUT_MESSAGE : null;
    }

    public int size() {
        return states.size();
    }

    /** Tests only. */
    public void clear() {
        states.clear();
    }
}

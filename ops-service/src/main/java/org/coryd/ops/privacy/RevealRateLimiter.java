package org.coryd.ops.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code ops.privacy.reveals-per-hour} (default 30) reveals per admin in any rolling hour - ID numbers, dates of
 * birth and bank account numbers share the one budget (docs/ops-console/privacy.md). Over it: 429 with when the next one
 * is allowed. Counted per ops-service instance, in memory (one instance today); a refused attempt doesn't count.
 */
@Component
public class RevealRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    private final int perHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> byAdmin = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RevealRateLimiter(@Value("${ops.privacy.reveals-per-hour:30}") int perHour) {
        this(perHour, Clock.systemUTC());
    }

    RevealRateLimiter(int perHour, Clock clock) {
        this.perHour = Math.max(1, perHour);
        this.clock = clock;
    }

    /** Takes one reveal from this admin's budget, or throws 429. */
    public void acquire(String adminId) {
        Instant now = clock.instant();
        Deque<Instant> recent = byAdmin.computeIfAbsent(adminId == null ? "?" : adminId, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= perHour) {
                long minutes = Math.max(1, Duration.between(now, recent.peekFirst().plus(WINDOW)).toMinutes() + 1);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You've revealed " + perHour
                        + " details in the last hour - try again in " + minutes + " minute" + (minutes == 1 ? "" : "s"));
            }
            recent.addLast(now);
        }
    }

    /** Gives one back - the reveal didn't happen (bad input, nothing on file, owning service down). */
    public void release(String adminId) {
        Deque<Instant> recent = byAdmin.get(adminId == null ? "?" : adminId);
        if (recent != null) {
            synchronized (recent) {
                recent.pollLast();
            }
        }
    }
}

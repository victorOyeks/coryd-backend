package org.coryd.ops.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every open console's Server-Sent Events stream on this instance, and the last few hundred events sent to them. Event ids
 * are "{instance}-{sequence}": a console reconnecting with Last-Event-ID gets what it missed replayed, in order; one whose
 * id is from another instance, from before a restart, or older than the buffer gets a "resync" event instead and reloads
 * its lists. Every connection opens with "hello" (the latest badge counts), gets a comment heartbeat every few seconds so
 * proxies keep it open, and is ended with "bye" at max-connection-minutes - EventSource then reconnects with a fresh token.
 */
@Slf4j
@Component
public class OpsEventStream {

    public static final String HELLO = "hello";
    public static final String RESYNC = "resync";
    public static final String BYE = "bye";
    public static final String COUNTS = "counts";

    record Event(long seq, String id, String type, Object data, Instant at) {
    }

    private record Subscriber(String connectionId, String adminId, SseEmitter emitter, Instant expiresAt) {
    }

    private final String instance = Long.toString(System.currentTimeMillis(), 36) + UUID.randomUUID().toString().substring(0, 4);
    private final AtomicLong sequence = new AtomicLong();
    private final Deque<Event> recent = new ArrayDeque<>();
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final AtomicReference<Object> latestCounts = new AtomicReference<>();
    private final ObjectMapper objectMapper;
    private final int replaySize;
    private final Duration maxConnection;
    private final long heartbeatSeconds;

    public OpsEventStream(ObjectMapper objectMapper,
                          @Value("${ops.stream.replay-size:500}") int replaySize,
                          @Value("${ops.stream.max-connection-minutes:30}") long maxConnectionMinutes,
                          @Value("${ops.stream.heartbeat-seconds:15}") long heartbeatSeconds) {
        this.objectMapper = objectMapper;
        this.replaySize = replaySize;
        this.maxConnection = Duration.ofMinutes(maxConnectionMinutes);
        this.heartbeatSeconds = heartbeatSeconds;
    }

    /** Opens a stream for an admin: hello, then anything missed since lastEventId (or resync), then live events. */
    public SseEmitter connect(String adminId, String lastEventId) {
        Instant expiresAt = Instant.now().plus(maxConnection);
        SseEmitter emitter = new SseEmitter(maxConnection.plusMinutes(1).toMillis());
        String connectionId = UUID.randomUUID().toString();
        Subscriber subscriber = new Subscriber(connectionId, adminId, emitter, expiresAt);
        emitter.onCompletion(() -> subscribers.remove(connectionId));
        emitter.onTimeout(() -> subscribers.remove(connectionId));
        emitter.onError(e -> subscribers.remove(connectionId));

        // Registered before the replay is read, under the same lock as publish, so nothing falls between the two.
        List<Event> replay;
        boolean resync = false;
        synchronized (recent) {
            subscribers.put(connectionId, subscriber);
            replay = new ArrayList<>();
            if (lastEventId != null && !lastEventId.isBlank()) {
                Long lastSeq = seqOf(lastEventId);
                Event oldest = recent.peekFirst();
                if (lastSeq == null || (oldest != null && lastSeq < oldest.seq() - 1) || lastSeq > sequence.get()) {
                    resync = true;
                } else {
                    for (Event e : recent) {
                        if (e.seq() > lastSeq) {
                            replay.add(e);
                        }
                    }
                }
            }
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("connectionId", connectionId);
            hello.put("serverTime", Instant.now());
            hello.put("expiresAt", expiresAt);
            hello.put("heartbeatSeconds", heartbeatSeconds);
            hello.put("lastEventId", currentId());
            hello.put("replayed", replay.size());
            hello.put("counts", latestCounts.get());
            try {
                send(subscriber, null, HELLO, hello);
                if (resync) {
                    send(subscriber, null, RESYNC, Map.of("reason", "Missed events can't be replayed - reload the SOS, reports and trip lists"));
                }
                for (Event e : replay) {
                    send(subscriber, e.id(), e.type(), e.data());
                }
            } catch (IOException e) {
                subscribers.remove(connectionId);
                emitter.completeWithError(e);
            }
        }
        log.info("Ops stream opened for admin {} ({} open on this instance, {} replayed{})", adminId, subscribers.size(), replay.size(),
                resync ? ", resync" : "");
        return emitter;
    }

    /** To every open console, and into the replay buffer. */
    public void publish(String type, Object data) {
        synchronized (recent) {
            long seq = sequence.incrementAndGet();
            Event event = new Event(seq, instance + "-" + seq, type, data, Instant.now());
            recent.addLast(event);
            while (recent.size() > replaySize) {
                recent.removeFirst();
            }
            if (COUNTS.equals(type)) {
                latestCounts.set(data);
            }
            for (Subscriber s : List.copyOf(subscribers.values())) {
                try {
                    send(s, event.id(), type, data);
                } catch (Exception e) {
                    drop(s, e);
                }
            }
        }
    }

    /** A comment line every heartbeat-seconds keeps nginx and the gateway from closing an idle stream; ends streams past their time. */
    @Scheduled(fixedRateString = "${ops.stream.heartbeat-seconds:15}000")
    public void heartbeat() {
        Instant now = Instant.now();
        for (Subscriber s : List.copyOf(subscribers.values())) {
            try {
                if (now.isAfter(s.expiresAt())) {
                    send(s, null, BYE, Map.of("reason", "EXPIRED", "message", "Stream ended after its time - reconnect with a new stream token"));
                    subscribers.remove(s.connectionId());
                    s.emitter().complete();
                } else {
                    synchronized (s.emitter()) {
                        s.emitter().send(SseEmitter.event().comment("hb " + now));
                    }
                }
            } catch (Exception e) {
                drop(s, e);
            }
        }
    }

    public int openStreams() {
        return subscribers.size();
    }

    String currentId() {
        long seq = sequence.get();
        return seq == 0 ? null : instance + "-" + seq;
    }

    private void send(Subscriber s, String id, String type, Object data) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(type).data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON);
        if (id != null) {
            event.id(id);
        }
        synchronized (s.emitter()) {
            s.emitter().send(event);
        }
    }

    private void drop(Subscriber s, Exception e) {
        subscribers.remove(s.connectionId());
        try {
            s.emitter().completeWithError(e);
        } catch (Exception ignored) {
            // already closed
        }
    }

    private Long seqOf(String lastEventId) {
        int dash = lastEventId.lastIndexOf('-');
        if (dash <= 0 || !lastEventId.substring(0, dash).equals(instance)) {
            return null;
        }
        try {
            return Long.parseLong(lastEventId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

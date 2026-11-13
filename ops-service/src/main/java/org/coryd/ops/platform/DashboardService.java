package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * GET /ops/dashboard (docs/ops-console/dashboard-reports.md): three calls in parallel - trip-service's dashboard numbers,
 * payment-service's finance summary, profile-service's verification pipeline - put together into the Figma dashboard's
 * shape, and cached for a short while per range so a room full of open consoles costs one set of queries. A service that
 * doesn't answer leaves its sections null and is named in unavailable; the rest still shows.
 */
@Slf4j
@Service
public class DashboardService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final PlatformClient client;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant at, ObjectNode value) {
    }

    public DashboardService(PlatformClient client, ObjectMapper objectMapper,
                            @Value("${ops.dashboard.cache-seconds:60}") long cacheSeconds) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(Math.max(0, cacheSeconds));
    }

    public ObjectNode dashboard(LocalDate from, LocalDate to, boolean fresh) {
        LocalDate end = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > 366) {
            throw new BadRequestException("The range can be at most 366 days");
        }
        String key = start + "|" + end;
        Cached hit = cache.get(key);
        if (!fresh && hit != null && hit.at().plus(ttl).isAfter(Instant.now())) {
            return hit.value().deepCopy().put("cachedAt", hit.at().toString());
        }
        ObjectNode built = build(start, end);
        cache.put(key, new Cached(Instant.now(), built));
        if (cache.size() > 200) {
            cache.entrySet().removeIf(e -> e.getValue().at().plus(ttl).isBefore(Instant.now()));
        }
        return built.deepCopy().put("cachedAt", (String) null);
    }

    private ObjectNode build(LocalDate from, LocalDate to) {
        Params range = Params.of().put("from", from).put("to", to);
        CompletableFuture<JsonNode> trip = Parallel.call(() -> client.get(PlatformClient.Service.TRIP, "/internal/ops/dashboard", range.build()));
        CompletableFuture<JsonNode> money = Parallel.call(() -> client.get(PlatformClient.Service.PAYMENT, "/internal/ops/money/finance/summary",
                Params.of().put("from", from).put("to", to).build()));
        CompletableFuture<JsonNode> pipeline = Parallel.call(() -> client.get(PlatformClient.Service.PROFILE, "/internal/ops/pipeline", null));
        List<String> unavailable = new ArrayList<>();
        JsonNode t = join(trip, "trip-service", unavailable);
        JsonNode m = join(money, "payment-service", unavailable);
        JsonNode p = join(pipeline, "profile-service", unavailable);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("from", from.toString()).put("to", to.toString()).put("timezone", LAGOS.getId()).put("generatedAt", Instant.now().toString());
        out.set("trips", t == null ? null : t.get("trips"));
        out.set("seats", t == null ? null : t.get("seats"));

        // A section fed by one service is null when that service didn't answer. drivers / riders mix two: each keeps every
        // field, the missing service's ones null, and the section is null only when both are down - never {} or fields left out.
        if (p == null && t == null) {
            out.putNull("drivers");
            out.putNull("riders");
        } else {
            ObjectNode drivers = out.putObject("drivers");
            number(drivers, "total", p, "driversTotal");
            number(drivers, "verified", p, "driversVerified");
            number(drivers, "suspended", p, "driversSuspended");
            number(drivers, "activeLast7Days", p, "driversActiveLast7Days");
            JsonNode td = t == null ? null : t.path("drivers");
            number(drivers, "activeInRange", td, "activeInRange");
            number(drivers, "onTripNow", td, "onTripNow");
            number(drivers, "onlineNow", td, "onlineNow");
            ObjectNode riders = out.putObject("riders");
            number(riders, "total", p, "ridersTotal");
            number(riders, "suspended", p, "ridersSuspended");
            number(riders, "activeLast7Days", p, "ridersActiveLast7Days");
            JsonNode tr = t == null ? null : t.path("riders");
            number(riders, "activeInRange", tr, "activeInRange");
            number(riders, "repeatInRange", tr, "repeatInRange");
        }

        if (m != null) {
            ObjectNode revenue = objectMapper.createObjectNode();
            revenue.set("grossBookings", m.path("grossBookings").get("amount"));
            revenue.set("payments", m.path("grossBookings").get("count"));
            revenue.set("refunds", m.path("refunds").get("amount"));
            revenue.set("netCollected", m.get("netCollected"));
            revenue.set("tripGross", m.path("commission").get("tripGross"));
            revenue.set("commission", m.path("commission").get("commission"));
            revenue.set("driverNet", m.path("commission").get("driverNet"));
            out.set("revenue", revenue);
        } else {
            out.putNull("revenue");
        }
        out.set("activity", t == null ? null : t.get("bookings"));
        if (p != null) {
            ObjectNode verification = objectMapper.createObjectNode();
            verification.set("driverQueue", p.get("driverQueue"));
            verification.set("riderIdentity", p.get("riderIdentity"));
            out.set("verification", verification);
        } else {
            out.putNull("verification");
        }
        if (t != null) {
            ObjectNode cancellations = out.putObject("cancellations");
            cancellations.set("bySource", t.get("cancellationsBySource"));
            cancellations.set("byKind", t.get("cancellationsByKind"));
        } else {
            out.putNull("cancellations");
        }
        out.set("daily", daily(from, to, t, m));
        out.set("recentActivity", t == null ? null : t.get("recentActivity"));
        ArrayNode missing = out.putArray("unavailable");
        unavailable.forEach(missing::add);
        return out;
    }

    /** Every daily row has all of these; a service that's down leaves its own null (and zero-activity days read 0 from it). */
    static final List<String> TRIP_DAILY = List.of("tripsPosted", "tripsCompleted", "tripsCancelled", "bookings", "seatsBooked",
            "cancellations", "grossFaresCompleted");
    static final List<String> MONEY_DAILY = List.of("grossBookings", "payments", "refunds", "commission", "payouts");

    private static void number(ObjectNode section, String field, JsonNode from, String key) {
        JsonNode v = from == null ? null : from.get(key);
        if (v == null || v.isMissingNode()) {
            section.putNull(field);
        } else {
            section.set(field, v);
        }
    }

    /** One row per Lagos day: trip-service's counts and payment-service's money side by side. */
    private ArrayNode daily(LocalDate from, LocalDate to, JsonNode trip, JsonNode money) {
        Map<String, ObjectNode> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            ObjectNode day = objectMapper.createObjectNode().put("date", d.toString());
            days.put(d.toString(), day);
        }
        for (ObjectNode day : days.values()) {
            for (String key : TRIP_DAILY) {
                if (trip == null) {
                    day.putNull(key);
                } else {
                    day.put(key, 0);
                }
            }
            for (String key : MONEY_DAILY) {
                if (money == null) {
                    day.putNull(key);
                } else {
                    day.put(key, 0);
                }
            }
        }
        if (trip != null) {
            for (JsonNode d : trip.path("daily")) {
                ObjectNode day = days.get(d.path("date").asText());
                if (day != null) {
                    d.fields().forEachRemaining(f -> {
                        if (!f.getKey().equals("date")) {
                            day.set(f.getKey(), f.getValue());
                        }
                    });
                }
            }
        }
        if (money != null) {
            for (JsonNode d : money.path("daily")) {
                ObjectNode day = days.get(d.path("date").asText());
                if (day != null) {
                    day.set("grossBookings", d.get("grossBookings"));
                    day.set("payments", d.get("payments"));
                    day.set("refunds", d.get("refunds"));
                    day.set("commission", d.get("commission"));
                    day.set("payouts", d.get("payouts"));
                }
            }
        }
        ArrayNode out = objectMapper.createArrayNode();
        days.values().forEach(out::add);
        return out;
    }

    private JsonNode join(CompletableFuture<JsonNode> future, String service, List<String> unavailable) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof org.coryd.ops.client.DownstreamException d && d.getStatus().value() == 403) {
                throw d;
            }
            log.warn("Dashboard: {} didn't answer - showing the rest: {}", service, cause.getMessage());
            unavailable.add(service);
            return null;
        }
    }

    /** Tests only. */
    public void clearCache() {
        cache.clear();
    }
}

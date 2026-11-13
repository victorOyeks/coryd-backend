package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GET /ops/system/health (docs/ops-console/system-health.md): each service's actuator health, consumer lag per CoRyd group,
 * every scheduled job's last run, and provider failure counts - gathered in parallel, a few seconds at most, and
 * summarised into alerts the console can show at the top.
 */
@Service
public class SystemHealthService {

    static final List<PlatformClient.Service> WITH_JOBS = List.of(PlatformClient.Service.TRIP, PlatformClient.Service.PAYMENT,
            PlatformClient.Service.PROFILE, PlatformClient.Service.NOTIFICATION);
    static final long LAG_ALERT = 1000;
    static final double FAILURE_RATE_ALERT = 0.2;

    private final PlatformClient client;
    private final KafkaLagReader lagReader;
    private final ObjectMapper objectMapper;
    private final HealthEndpoint healthEndpoint;
    private final RestClient plain = HttpTimeouts.builder(Duration.ofSeconds(2), Duration.ofSeconds(4)).build();
    private final String serviceName;

    public SystemHealthService(PlatformClient client, KafkaLagReader lagReader, ObjectMapper objectMapper, HealthEndpoint healthEndpoint,
                               @Value("${spring.application.name:ops-service}") String serviceName) {
        this.client = client;
        this.lagReader = lagReader;
        this.objectMapper = objectMapper;
        this.healthEndpoint = healthEndpoint;
        this.serviceName = serviceName;
    }

    public ObjectNode health(int hours) {
        int window = Math.max(1, Math.min(hours, 24 * 30));
        Map<PlatformClient.Service, CompletableFuture<JsonNode>> health = new LinkedHashMap<>();
        for (PlatformClient.Service s : PlatformClient.Service.values()) {
            health.put(s, CompletableFuture.supplyAsync(() -> actuator(s)));
        }
        Map<PlatformClient.Service, CompletableFuture<JsonNode>> status = new LinkedHashMap<>();
        for (PlatformClient.Service s : WITH_JOBS) {
            status.put(s, Parallel.call(() -> client.get(s, "/internal/system/status", Params.of().put("hours", window).build())));
        }
        CompletableFuture<KafkaLagReader.Lag> lag = CompletableFuture.supplyAsync(lagReader::read);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("at", Instant.now().toString()).put("windowHours", window);
        ArrayNode alerts = objectMapper.createArrayNode();

        ArrayNode services = out.putArray("services");
        ObjectNode self = services.addObject().put("service", serviceName);
        self.put("status", healthEndpoint.health().getStatus().getCode());
        for (Map.Entry<PlatformClient.Service, CompletableFuture<JsonNode>> e : health.entrySet()) {
            JsonNode h = join(e.getValue(), 6);
            ObjectNode row = services.addObject().put("service", e.getKey().label);
            if (h == null) {
                row.put("status", "UNREACHABLE");
            } else {
                row.put("status", h.path("status").asText("UNKNOWN"));
                if (h.has("components")) {
                    ObjectNode components = row.putObject("components");
                    h.get("components").fields().forEachRemaining(c -> components.put(c.getKey(), c.getValue().path("status").asText()));
                }
            }
            if (!"UP".equals(row.path("status").asText())) {
                alerts.addObject().put("level", "CRITICAL").put("kind", "SERVICE").put("message", e.getKey().label + " is " + row.path("status").asText());
            }
        }

        KafkaLagReader.Lag kafka = join(lag, 20);
        ObjectNode kafkaNode = out.putObject("kafka");
        if (kafka == null) {
            kafkaNode.put("reachable", false).put("error", "Timed out reading consumer lag");
        } else {
            kafkaNode.put("reachable", kafka.reachable()).put("error", kafka.error());
            kafkaNode.set("groups", objectMapper.valueToTree(kafka.groups()));
            kafkaAlerts(kafka.groups(), alerts);
        }
        if (kafka == null || !kafka.reachable()) {
            alerts.addObject().put("level", "WARNING").put("kind", "KAFKA").put("message", "Consumer lag couldn't be read");
        }

        ArrayNode jobs = out.putArray("jobs");
        ArrayNode integrations = out.putArray("integrations");
        for (Map.Entry<PlatformClient.Service, CompletableFuture<JsonNode>> e : status.entrySet()) {
            JsonNode s = join(e.getValue(), 10);
            if (s == null) {
                jobs.addObject().put("service", e.getKey().label).put("error", "Couldn't read this service's jobs");
                continue;
            }
            for (JsonNode job : s.path("jobs")) {
                ObjectNode row = ((ObjectNode) job.deepCopy()).put("service", e.getKey().label);
                jobs.add(row);
                if ("FAILED".equals(job.path("lastRun").path("lastStatus").asText())) {
                    alerts.addObject().put("level", "WARNING").put("kind", "JOB")
                            .put("message", e.getKey().label + " " + job.path("job").asText() + " failed its last run: "
                                    + job.path("lastRun").path("lastError").asText(""));
                }
            }
            ArrayNode reported = objectMapper.createArrayNode();
            s.path("integrations").forEach(reported::add);
            for (JsonNode i : withEveryProvider(e.getKey(), reported)) {
                integrations.add(((ObjectNode) i.deepCopy()).put("service", e.getKey().label));
                long attempts = i.path("attempts").asLong(0);
                long failures = i.path("failures").asLong(0);
                if (failures > 0 && attempts >= 5 && failures >= attempts * FAILURE_RATE_ALERT) {
                    alerts.addObject().put("level", "WARNING").put("kind", "INTEGRATION")
                            .put("message", i.path("integration").asText() + " " + i.path("operation").asText() + ": " + failures + " of "
                                    + attempts + " failed in the last " + window + "h");
                }
            }
        }
        out.set("alerts", alerts);
        boolean critical = false;
        for (JsonNode a : alerts) {
            critical |= "CRITICAL".equals(a.path("level").asText());
        }
        out.put("overall", critical ? "DOWN" : alerts.isEmpty() ? "OK" : "DEGRADED");
        return out;
    }

    /**
     * Lag alerts for real consumers only. Ephemeral groups (ops-service's per-instance live-stream groups) are skipped: an
     * instance that restarted leaves its old group EMPTY with a growing "lag" that nobody will ever read - that was the
     * false DOWN. A real group with lag and no members is a consumer that stopped: CRITICAL.
     */
    static void kafkaAlerts(List<KafkaLagReader.GroupLag> groups, ArrayNode alerts) {
        for (KafkaLagReader.GroupLag g : groups) {
            if (g.ephemeral()) {
                continue;
            }
            if (g.lag() >= LAG_ALERT) {
                alerts.addObject().put("level", "WARNING").put("kind", "KAFKA_LAG")
                        .put("message", g.group() + " is " + g.lag() + " messages behind");
            }
            if (g.members() == 0 && g.lag() > 0) {
                alerts.addObject().put("level", "CRITICAL").put("kind", "KAFKA_CONSUMER")
                        .put("message", g.group() + " has no consumers and " + g.lag() + " messages waiting");
            }
        }
    }

    /** Every provider each service talks to, so one with nothing in the window still shows - with zero counts. */
    static final Map<PlatformClient.Service, List<List<String>>> PROVIDERS = Map.of(
            PlatformClient.Service.PAYMENT, List.of(List.of("PAYSTACK", "charges"), List.of("PAYSTACK", "refunds"),
                    List.of("PAYSTACK", "settlements"), List.of("PAYSTACK", "ops refunds and retries")),
            PlatformClient.Service.PROFILE, List.of(List.of("SMILE_ID", "verification checks")),
            PlatformClient.Service.NOTIFICATION, List.of(List.of("TERMII", "sms"), List.of("TERMII", "voice"),
                    List.of("TERMII_WHATSAPP", "whatsapp"), List.of("FCM", "push"), List.of("SMTP", "email")));

    ArrayNode withEveryProvider(PlatformClient.Service service, ArrayNode reported) {
        for (List<String> p : PROVIDERS.getOrDefault(service, List.of())) {
            boolean present = false;
            for (JsonNode i : reported) {
                present |= p.get(0).equals(i.path("integration").asText()) && p.get(1).equalsIgnoreCase(i.path("operation").asText());
            }
            if (!present) {
                ObjectNode zero = reported.addObject().put("integration", p.get(0)).put("operation", p.get(1)).put("attempts", 0)
                        .put("failures", 0);
                zero.putNull("lastFailureAt");
                zero.putNull("lastError");
            }
        }
        return reported;
    }

    private JsonNode actuator(PlatformClient.Service service) {
        String url = client.url(service) + "/actuator/health";
        try {
            return plain.get().uri(url).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            try {
                return objectMapper.readTree(e.getResponseBodyAsString());
            } catch (Exception ignored) {
                return objectMapper.createObjectNode().put("status", "HTTP_" + e.getStatusCode().value());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T join(CompletableFuture<T> future, int seconds) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}

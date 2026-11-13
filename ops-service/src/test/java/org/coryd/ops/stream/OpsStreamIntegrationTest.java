package org.coryd.ops.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.coryd.common.enums.UserRole;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsCountsChangedEvent;
import org.coryd.common.kafka.events.OpsSosChangedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripSafetyEscalatedEvent;
import org.coryd.common.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's live stream over real HTTP and a real Kafka broker: a stream token is issued only to an ops admin, opens the
 * stream, and nothing else does; events published on Kafka by trip-service reach a connected console as named SSE events;
 * heartbeats keep it open; a reconnect with Last-Event-ID replays what was missed, and one that can't be replayed is told to
 * resync.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "ops.stream.heartbeat-seconds=1"
})
@Testcontainers
class OpsStreamIntegrationTest {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaListenerEndpointRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<SseClient> clients = new ArrayList<>();
    private KafkaTemplate<String, Object> producer;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        // The stream reads from the latest offset - publish only once it has its partitions.
        MessageListenerContainer container = registry.getListenerContainers().stream()
                .filter(c -> c.getGroupId() != null && c.getGroupId().startsWith("coryd-ops-stream")).findFirst().orElseThrow();
        long deadline = System.currentTimeMillis() + 60_000;
        while ((container.getAssignedPartitions() == null || container.getAssignedPartitions().size() < 10)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(container.getAssignedPartitions()).hasSize(10);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(SseClient::close);
        producer.destroy();
    }

    @Test
    void aStreamTokenIsForOpsAdminsAndOpensTheStreamNothingElseDoes() throws Exception {
        assertThat(post("/ops/stream/token", null).statusCode()).isEqualTo(401);
        assertThat(post("/ops/stream/token", bearer(UserRole.RIDER)).statusCode()).isEqualTo(403);
        HttpResponse<String> issued = post("/ops/stream/token", bearer(UserRole.OPS_ADMIN));
        assertThat(issued.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(issued.body()).get("data");
        assertThat(Instant.parse(data.get("expiresAt").asText())).isBetween(Instant.now().plusSeconds(30), Instant.now().plusSeconds(61));

        assertThat(openStatus("/ops/stream")).as("no token").isEqualTo(401);
        assertThat(openStatus("/ops/stream?token=garbage")).isEqualTo(401);
        String forged = new StreamTokenService("some-other-secret", 60, objectMapper).issue("admin-1", "Ada").token();
        assertThat(openStatus("/ops/stream?token=" + forged)).as("signed with another key").isEqualTo(401);
        String expired = new StreamTokenService("test-secret-test-secret-test-secret-test-secret-0123456789", -1, objectMapper)
                .issue("admin-1", "Ada").token();
        assertThat(openStatus("/ops/stream?token=" + expired)).isEqualTo(401);
        assertThat(openStatus("/ops/stream?token=" + jwtUtil.generateToken("admin-1", "0800", "Ada", "Ops", UserRole.OPS_ADMIN)))
                .as("an access token isn't a stream token").isEqualTo(401);

        SseClient stream = connect(data.get("token").asText(), null);
        SseEvent hello = stream.next("hello");
        assertThat(objectMapper.readTree(hello.data()).get("heartbeatSeconds").asInt()).isEqualTo(1);
    }

    @Test
    void eventsOnKafkaReachAConnectedConsoleWithHeartbeatsInBetween() throws Exception {
        SseClient stream = connect(token(), null);
        stream.next("hello");

        producer.send(KafkaTopics.SOS_TRIGGERED, SosTriggeredEvent.builder().incidentId("sos-1").triggeredBy("rider-1").triggeredByRole("RIDER")
                .routePostId("route-1").bookingId("booking-1").routeSummary("Ikorodu → VI").driverName("Emeka").driverPhone("0805")
                .latitude(6.5).longitude(3.4).triggeredAt(LocalDateTime.now()).contacts(List.of()).build()).get();
        SseEvent raised = stream.next("sos.raised");
        JsonNode sos = objectMapper.readTree(raised.data());
        assertThat(sos.get("incidentId").asText()).isEqualTo("sos-1");
        assertThat(sos.get("kind").asText()).isEqualTo("SOS");
        assertThat(sos.get("routeSummary").asText()).isEqualTo("Ikorodu → VI");
        assertThat(raised.id()).isNotBlank();

        producer.send(KafkaTopics.OPS_SOS_CHANGED, "sos-1", OpsSosChangedEvent.builder().incidentId("sos-1").kind("SOS").action("ACKNOWLEDGED")
                .status("ACKNOWLEDGED").actorId("admin-1").actorName("Ada Ops").at(Instant.now()).build()).get();
        JsonNode updated = objectMapper.readTree(stream.next("sos.updated").data());
        assertThat(updated.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(updated.get("actorName").asText()).isEqualTo("Ada Ops");

        producer.send(KafkaTopics.OPS_SOS_CHANGED, "sos-2", OpsSosChangedEvent.builder().incidentId("sos-2").kind("SAFETY_CHECK").action("RAISED")
                .status("OPEN").routePostId("route-2").safetyAlertId("alert-2").at(Instant.now()).build()).get();
        assertThat(objectMapper.readTree(stream.next("sos.raised").data()).get("kind").asText()).isEqualTo("SAFETY_CHECK");

        producer.send(KafkaTopics.TRIP_SAFETY_ESCALATED, TripSafetyEscalatedEvent.builder().alertId("alert-2").routePostId("route-2")
                .kind("STOPPED").unanswered(2).build()).get();
        assertThat(objectMapper.readTree(stream.next("safety.escalated").data()).get("unanswered").asInt()).isEqualTo(2);

        producer.send(KafkaTopics.OPS_COUNTS_CHANGED, "ops-counts", OpsCountsChangedEvent.builder().sosOpen(2).sosAcknowledged(1)
                .stuckTrips(3).reportsOpen(4).computedAt(Instant.now()).build()).get();
        JsonNode counts = objectMapper.readTree(stream.next("counts").data());
        assertThat(counts.get("sos").get("open").asLong()).isEqualTo(2);
        assertThat(counts.get("stuckTrips").asLong()).isEqualTo(3);
        assertThat(counts.get("reports").get("open").asLong()).isEqualTo(4);

        assertThat(stream.awaitComment(Duration.ofSeconds(5))).as("a heartbeat comment at least every second").startsWith("hb");

        SseClient late = connect(token(), null);
        JsonNode hello = objectMapper.readTree(late.next("hello").data());
        assertThat(hello.get("counts").get("stuckTrips").asLong()).as("a console that connects late gets the latest badges").isEqualTo(3);
    }

    @Test
    void aReconnectWithLastEventIdGetsWhatItMissedAndAnUnknownIdIsToldToResync() throws Exception {
        SseClient first = connect(token(), null);
        first.next("hello");
        SseClient watcher = connect(token(), null);
        watcher.next("hello");
        producer.send(KafkaTopics.OPS_SOS_CHANGED, "sos-9", changed("sos-9", "ACKNOWLEDGED")).get();
        SseEvent seen = first.next("sos.updated");
        first.close();

        producer.send(KafkaTopics.OPS_SOS_CHANGED, "sos-9", changed("sos-9", "ESCALATED")).get();
        producer.send(KafkaTopics.OPS_SOS_CHANGED, "sos-9", changed("sos-9", "RESOLVED")).get();
        // Both have reached the stream (the watcher saw them) while the first console was gone.
        watcher.next("sos.updated");
        watcher.next("sos.updated");
        watcher.next("sos.updated");

        SseClient again = connect(token(), seen.id());
        JsonNode hello = objectMapper.readTree(again.next("hello").data());
        assertThat(hello.get("replayed").asInt()).isEqualTo(2);
        assertThat(objectMapper.readTree(again.next("sos.updated").data()).get("status").asText()).isEqualTo("ESCALATED");
        assertThat(objectMapper.readTree(again.next("sos.updated").data()).get("status").asText()).isEqualTo("RESOLVED");

        SseClient stranger = connect(token(), "another-instance-42");
        stranger.next("hello");
        assertThat(stranger.next("resync").data()).contains("reload");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static OpsSosChangedEvent changed(String id, String status) {
        return OpsSosChangedEvent.builder().incidentId(id).kind("SOS").action(status).status(status).at(Instant.now()).build();
    }

    private String token() throws Exception {
        return objectMapper.readTree(post("/ops/stream/token", bearer(UserRole.OPS_ADMIN)).body()).get("data").get("token").asText();
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", "08000000000", "Ada", "Ops", role);
    }

    private HttpResponse<String> post(String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).POST(HttpRequest.BodyPublishers.noBody());
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int openStatus(String path) throws Exception {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = r.statusCode();
        r.body().close();
        return status;
    }

    private SseClient connect(String token, String lastEventId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ops/stream?token=" + token))
                .header("Accept", "text/event-stream").GET();
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<Stream<String>> response = http.send(b.build(), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        SseClient client = new SseClient(response.body());
        clients.add(client);
        return client;
    }

    record SseEvent(String id, String name, String data) {
    }

    /** Reads an SSE stream on its own thread into events and comments. */
    static final class SseClient {
        private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();
        private final Stream<String> lines;
        private final Thread reader;

        SseClient(Stream<String> lines) {
            this.lines = lines;
            this.reader = Thread.ofVirtual().start(() -> {
                String id = null;
                String name = null;
                StringBuilder data = new StringBuilder();
                try {
                    for (String line : (Iterable<String>) lines::iterator) {
                        if (line.isEmpty()) {
                            if (name != null || data.length() > 0) {
                                events.add(new SseEvent(id, name, data.toString()));
                            }
                            id = null;
                            name = null;
                            data.setLength(0);
                        } else if (line.startsWith(":")) {
                            comments.add(line.substring(1));
                        } else if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                        } else if (line.startsWith("event:")) {
                            name = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5));
                        }
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
        }

        /** The next event called {@code name}, skipping others; fails after 20 seconds. */
        SseEvent next(String name) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                SseEvent e = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (e != null && name.equals(e.name())) {
                    return e;
                }
            }
            throw new AssertionError("No '" + name + "' event within 20s");
        }

        String awaitComment(Duration within) throws InterruptedException {
            String c = comments.poll(within.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(c).as("comment").isNotNull();
            return c;
        }

        void close() {
            lines.close();
            reader.interrupt();
        }
    }
}

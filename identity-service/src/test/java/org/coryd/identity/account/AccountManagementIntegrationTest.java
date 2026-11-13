package org.coryd.identity.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.IdentityTokenRevocationSource;
import org.coryd.common.security.revocation.TokenRevocationBootstrap;
import org.coryd.common.security.revocation.TokenRevocationKafkaListener;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.identity.client.DeletionBlockerClient;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account management end to end, against a real Postgres (identity's own migrations) and a real Kafka broker: suspend,
 * reinstate, sign out everywhere and delete, with the account history, and the revocation feed reaching "another service" -
 * a JwtFilter with its own registry, fed only by the compacted topic or by the identity snapshot over HTTP, as every other
 * service's is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "services.internal.api-key=" + AccountManagementIntegrationTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "coryd.token-revocation.refresh-minutes=60"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountManagementIntegrationTest {

    static final String KEY = "test-internal-key";
    private static final String PASSWORD = "Sup3r-secret!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @LocalServerPort int port;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean DeletionBlockerClient deletionBlockerClient;

    private String ops;

    @BeforeEach
    void setUp() {
        ops = "Bearer " + jwtUtil.generateToken("ops-1", null, "Ada", "Ops", UserRole.OPS_ADMIN);
        when(deletionBlockerClient.blockers(anyString(), anyBoolean())).thenReturn(List.of());
    }

    // ---- suspend: every session ends, here and in every other service --------------------------------------------

    @Test
    void suspendingEndsEverySessionHereAndInAnotherServiceWithinSeconds() throws Exception {
        User user = user();
        Session session = login(user, "phone-1");
        mockMvc.perform(get("/auth/me").header("Authorization", session.bearer())).andExpect(status().isOk());

        // "trip-service": its own registry, fed only by the topic
        TokenRevocationRegistry elsewhere = new TokenRevocationRegistry();
        TokenRevocationKafkaListener feed = new TokenRevocationKafkaListener(elsewhere, objectMapper, kafka.getBootstrapServers(), "trip-test");
        feed.start();
        try {
            mockMvc.perform(internal(post("/internal/users/{id}/suspend", user.getId()))
                            .content("{\"reason\":\"Threatened a driver\",\"opsUserId\":\"ops-1\",\"opsUserName\":\"Ada Ops\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/auth/me").header("Authorization", session.bearer()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("This account has been suspended. Contact CoRyd support."));
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + user.getPhone() + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("This account has been suspended. Contact CoRyd support."));
            mockMvc.perform(post("/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + session.refreshToken() + "\",\"deviceId\":\"phone-1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("This account has been suspended. Contact CoRyd support."));

            long issuedAt = JwtUtil.issuedAtMillis(claims(session.accessToken()));
            await(() -> elsewhere.refusal(user.getId(), issuedAt) != null, "the suspension reaches the other service through Kafka");
            assertThat(elsewhere.refusal(user.getId(), issuedAt)).isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/routes");
            request.addHeader("Authorization", session.bearer());
            new JwtFilter(jwtUtil, elsewhere).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as("the other service refuses the token").isNull();
            assertThat(request.getAttribute(JwtFilter.REFUSAL_ATTRIBUTE)).isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);
        } finally {
            feed.stop();
            SecurityContextHolder.clearContext();
        }

        JsonNode history = data(mockMvc.perform(internal(get("/internal/users/{id}/account-actions", user.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history.get("content")).hasSize(1);
        assertThat(history.at("/content/0/action").asText()).isEqualTo("SUSPENDED");
        assertThat(history.at("/content/0/reason").asText()).isEqualTo("Threatened a driver");
        assertThat(history.at("/content/0/actor/name").asText()).isEqualTo("Ada Ops");
        assertThat(history.at("/content/0/changes/0/after").asText()).isEqualTo("SUSPENDED");

        mockMvc.perform(internal(post("/internal/users/{id}/suspend", user.getId())).content("{\"reason\":\"again\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This account is already suspended"));
    }

    // ---- reinstate: a reason, and old tokens stay dead ----------------------------------------------------------

    @Test
    void reinstatingNeedsAReasonAndOnlyTokensIssuedAfterwardsWork() throws Exception {
        User user = user();
        Session before = login(user, "phone-1");
        suspend(user);

        mockMvc.perform(internal(post("/internal/users/{id}/reinstate", user.getId())).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("reason: is required"));
        mockMvc.perform(internal(post("/internal/users/{id}/reinstate", user.getId()))
                        .content("{\"reason\":\"Investigated - not him\",\"opsUserId\":\"ops-2\",\"opsUserName\":\"Bola Ops\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me").header("Authorization", before.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));

        Session after = login(user, "phone-1");
        mockMvc.perform(get("/auth/me").header("Authorization", after.bearer())).andExpect(status().isOk());

        JsonNode account = data(mockMvc.perform(internal(get("/internal/users/{id}/account", user.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(account.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(account.get("suspension").isNull()).isTrue();
        assertThat(account.get("suspensionHistory")).as("the suspension survives the reinstate").hasSize(1);
        assertThat(account.at("/suspensionHistory/0/reason").asText()).isEqualTo("Threatened a driver");
        assertThat(account.at("/suspensionHistory/0/suspendedBy/name").asText()).isEqualTo("Ada Ops");
        assertThat(account.at("/suspensionHistory/0/reinstateReason").asText()).isEqualTo("Investigated - not him");
        assertThat(account.at("/suspensionHistory/0/reinstatedBy/name").asText()).isEqualTo("Bola Ops");
        assertThat(account.get("sessionsValidAfter").isNull()).isFalse();

        mockMvc.perform(internal(post("/internal/users/{id}/reinstate", user.getId())).content("{\"reason\":\"twice\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This account isn't suspended"));
    }

    // ---- sign out everywhere -----------------------------------------------------------------------------------

    @Test
    void signingOutEverywhereEndsEverySessionAndDeviceButTheyCanSignInAgain() throws Exception {
        User user = user();
        Session phone = login(user, "phone-1");
        Session tablet = login(user, "tablet-1");

        mockMvc.perform(internal(post("/internal/users/{id}/sign-out-everywhere", user.getId())).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(post("/internal/users/{id}/sign-out-everywhere", user.getId()))
                        .content("{\"reason\":\"Reported a stolen phone\",\"opsUserId\":\"ops-1\",\"opsUserName\":\"Ada Ops\"}"))
                .andExpect(status().isOk());

        for (Session s : List.of(phone, tablet)) {
            mockMvc.perform(get("/auth/me").header("Authorization", s.bearer()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
        }
        assertThat(refreshTokenRepository.findByTokenAndRevokedFalse(phone.refreshToken())).isEmpty();
        assertThat(refreshTokenRepository.findByTokenAndRevokedFalse(tablet.refreshToken())).isEmpty();

        JsonNode account = data(mockMvc.perform(internal(get("/internal/users/{id}/account", user.getId())))
                .andReturn().getResponse().getContentAsString());
        assertThat(account.get("devices")).hasSize(2).allSatisfy(d -> assertThat(d.get("signedIn").asBoolean()).isFalse());
        assertThat(account.get("lastSeenAt").isNull()).isFalse();
        assertThat(account.get("roles").toString()).contains("RIDER");

        Session again = login(user, "phone-1");
        mockMvc.perform(get("/auth/me").header("Authorization", again.bearer())).andExpect(status().isOk());

        JsonNode history = data(mockMvc.perform(internal(get("/internal/users/{id}/account-actions", user.getId())))
                .andReturn().getResponse().getContentAsString());
        assertThat(history.at("/content/0/action").asText()).isEqualTo("SIGNED_OUT_EVERYWHERE");
        assertThat(history.at("/content/0/reason").asText()).isEqualTo("Reported a stolen phone");
    }

    // ---- the snapshot every other service loads at startup -----------------------------------------------------

    @Test
    void anotherServiceStartingUpLoadsTheSnapshotOverHttp() throws Exception {
        User user = user();
        Session session = login(user, "phone-1");
        suspend(user);

        // over real HTTP (MockMvc has no servlet path, so it never reaches the internal-key gate)
        java.net.http.HttpResponse<String> withoutKey = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/identity/internal/users/token-revocations"))
                        .header("Authorization", ops).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(withoutKey.statusCode()).as("internal key required").isEqualTo(403);

        TokenRevocationRegistry elsewhere = new TokenRevocationRegistry();
        TokenRevocationBootstrap bootstrap = new TokenRevocationBootstrap(elsewhere,
                List.of(new IdentityTokenRevocationSource("http://localhost:" + port + "/identity", KEY, jwtUtil)), 60, true);
        assertThat(bootstrap.loadOnce()).isTrue();
        assertThat(elsewhere.refusal(user.getId(), JwtUtil.issuedAtMillis(claims(session.accessToken()))))
                .isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);
        assertThat(elsewhere.refusal("someone-never-revoked", System.currentTimeMillis())).as("fails open for unknown users").isNull();
    }

    // ---- what ops-service records for edits made elsewhere -----------------------------------------------------

    @Test
    void editsMadeInOtherServicesAreRecordedButSuspensionsOnlyByTheirOwnCall() throws Exception {
        User user = user();
        mockMvc.perform(internal(post("/internal/users/{id}/account-actions", user.getId()))
                        .content("{\"action\":\"DETAILS_EDITED\",\"reason\":\"Typo in surname\",\"opsUserId\":\"ops-1\",\"opsUserName\":\"Ada Ops\","
                                + "\"changes\":[{\"field\":\"lastName\",\"before\":\"Okafr\",\"after\":\"Okafor\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes[0].before").value("Okafr"));
        mockMvc.perform(internal(post("/internal/users/{id}/account-actions", user.getId()))
                        .content("{\"action\":\"SUSPENDED\",\"reason\":\"sneaky\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(post("/internal/users/{id}/account-actions", "nobody"))
                        .content("{\"action\":\"DETAILS_EDITED\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        JsonNode history = data(mockMvc.perform(internal(get("/internal/users/{id}/account-actions", user.getId())))
                .andReturn().getResponse().getContentAsString());
        assertThat(history.get("totalElements").asInt()).isEqualTo(1);
        assertThat(history.at("/content/0/changes/0/field").asText()).isEqualTo("lastName");
    }

    // ---- deletion requests -------------------------------------------------------------------------------------

    @Test
    void aRefusedDeletionIsPendingWithItsReasonsAndCompletesWhenItGoesThrough() throws Exception {
        User user = user();
        Session session = login(user, "phone-1");
        when(deletionBlockerClient.blockers(anyString(), anyBoolean())).thenReturn(List.of("you're on a trip right now"));

        mockMvc.perform(delete("/auth/me").header("Authorization", session.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}")).andExpect(status().isBadRequest());

        JsonNode pending = data(mockMvc.perform(internal(get("/internal/account-deletions").param("status", "PENDING")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode request = find(pending.get("content"), user.getId());
        assertThat(request.get("requestedBy").asText()).isEqualTo("SELF");
        assertThat(request.at("/blockers/0").asText()).isEqualTo("you're on a trip right now");
        assertThat(request.get("phone").asText()).as("the account still exists").isEqualTo(user.getPhone());
        JsonNode account = data(mockMvc.perform(internal(get("/internal/users/{id}/account", user.getId())))
                .andReturn().getResponse().getContentAsString());
        assertThat(account.at("/pendingDeletion/requestId").asText()).isEqualTo(request.get("id").asText());

        when(deletionBlockerClient.blockers(anyString(), anyBoolean())).thenReturn(List.of());
        mockMvc.perform(internal(delete("/internal/users/{id}", user.getId()).param("opsUserId", "ops-1"))).andExpect(status().isOk());

        JsonNode done = data(mockMvc.perform(internal(get("/internal/account-deletions/{id}", request.get("id").asText())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("attempts").asInt()).isEqualTo(2);
        assertThat(done.get("completedBy").asText()).isEqualTo("ops-1");
        assertThat(done.get("phoneLast4").asText()).isEqualTo(user.getPhone().substring(user.getPhone().length() - 4));
        assertThat(done.get("phone").isNull()).as("nothing personal kept once deleted").isTrue();
        assertThat(done.get("blockers")).isEmpty();

        mockMvc.perform(get("/auth/me").header("Authorization", session.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
        mockMvc.perform(internal(get("/internal/account-deletions/{id}", "nope"))).andExpect(status().isNotFound());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private record Session(String accessToken, String refreshToken) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    private User user() {
        String phone = "080" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 100_000_000L);
        return userRepository.save(User.builder().phone(org.coryd.common.util.PhoneNumbers.normalize(phone))
                .firstName("Tunde").lastName("Bakare").passwordHash(passwordEncoder.encode(PASSWORD))
                .phoneVerifiedAt(Instant.now()).termsAcceptedAt(Instant.now()).roles(new java.util.HashSet<>(Set.of(UserRole.RIDER))).build());
    }

    private Session login(User user, String deviceId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("phone", user.getPhone(), "password", PASSWORD,
                "device", Map.of("deviceId", deviceId, "deviceName", deviceId, "platform", "ANDROID")));
        JsonNode auth = data(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Session(auth.get("accessToken").asText(), auth.get("refreshToken").asText());
    }

    private void suspend(User user) throws Exception {
        mockMvc.perform(internal(post("/internal/users/{id}/suspend", user.getId()))
                        .content("{\"reason\":\"Threatened a driver\",\"opsUserId\":\"ops-1\",\"opsUserName\":\"Ada Ops\"}"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiKeyFilter.HEADER, KEY).header("Authorization", ops).contentType(MediaType.APPLICATION_JSON);
    }

    private Claims claims(String token) {
        return jwtUtil.extractAllClaims(token);
    }

    private JsonNode data(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    private static JsonNode find(JsonNode items, String userId) {
        for (JsonNode item : items) {
            if (userId.equals(item.get("userId").asText())) {
                return item;
            }
        }
        throw new AssertionError("No request for " + userId + " in " + items);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting: " + what);
            }
            Thread.sleep(200);
        }
    }
}

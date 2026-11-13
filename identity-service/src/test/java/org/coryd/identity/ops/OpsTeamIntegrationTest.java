package org.coryd.identity.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.revocation.TokenRevocationKafkaListener;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.identity.client.DeletionBlockerClient;
import org.coryd.identity.entity.OpsInvite;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.OpsInviteRepository;
import org.coryd.identity.repository.UserRepository;
import org.coryd.identity.service.AccountActionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ops admin team end to end against identity's own migrations (real Postgres) and a real Kafka broker: invite by
 * email with a one-time link, accept, sign in by email with the ops role on the token, role changes and deactivation
 * with immediate revocation reaching "another service", every refusal, and the identity part of the audit feed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "services.internal.api-key=" + OpsTeamIntegrationTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "coryd.token-revocation.refresh-minutes=60",
        "coryd.ops.console-url=https://console.test/"
})
@AutoConfigureMockMvc
@Testcontainers
class OpsTeamIntegrationTest {

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

    /** Every invite email event seen, by user id - the raw link only travels there. */
    private static final Map<String, List<JsonNode>> invites = new ConcurrentHashMap<>();
    private static Thread inviteReader;
    private static volatile boolean reading = true;

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired OpsInviteRepository inviteRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AccountActionService accountActionService;
    @MockBean DeletionBlockerClient deletionBlockerClient;

    @BeforeAll
    static void readInvites() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "invite-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ObjectMapper mapper = new ObjectMapper();
        inviteReader = new Thread(() -> {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(KafkaTopics.OPS_ADMIN_INVITED));
                while (reading) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                        try {
                            invites.computeIfAbsent(record.key(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                    .add(mapper.readTree(record.value()));
                        } catch (Exception ignored) {
                            // not JSON - not ours
                        }
                    }
                }
            }
        });
        inviteReader.setDaemon(true);
        inviteReader.start();
    }

    @AfterAll
    static void stopReading() {
        reading = false;
    }

    // ---- invite -> accept -> sign in by email --------------------------------------------------------------------------

    @Test
    void anInvitedAdminSetsAPasswordOnceAndSignsInByEmailWithTheirOpsRoleOnTheToken() throws Exception {
        User boss = superAdmin();
        String email = "Ngozi." + UUID.randomUUID().toString().substring(0, 8) + "@CoRyd.ng";

        JsonNode invited = data(perform(internal(post("/internal/ops-admins/invites"), boss)
                .content(json(Map.of("email", email, "firstName", "Ngozi", "lastName", "Eze", "opsRole", "FINANCE",
                        "opsUserId", boss.getId(), "opsUserName", "Boss Admin"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String adminId = invited.get("userId").asText();
        assertThat(invited.get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(invited.get("status").asText()).isEqualTo("INVITED");
        assertThat(invited.get("opsRole").asText()).isEqualTo("FINANCE");
        assertThat(invited.get("invitedByName").asText()).isEqualTo("Boss Admin");
        assertThat(Instant.parse(invited.get("inviteExpiresAt").asText()))
                .isBetween(Instant.now().plus(Duration.ofHours(71)), Instant.now().plus(Duration.ofHours(73)));

        String token = inviteToken(adminId, 1);
        JsonNode event = invites.get(adminId).get(0);
        assertThat(event.get("link").asText()).startsWith("https://console.test/accept-invite?token=");
        assertThat(event.get("opsRoleLabel").asText()).isEqualTo("Finance");
        assertThat(inviteRepository.findFirstByUserIdOrderByCreatedAtDesc(adminId).orElseThrow().getTokenHash())
                .as("only the hash is stored").isNotEqualTo(token).hasSize(64);

        // not signed up yet
        perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", email, "password", PASSWORD, "role", "OPS_ADMIN"))))
                .andExpect(status().isBadRequest());

        perform(get("/auth/ops-invite").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Ngozi"))
                .andExpect(jsonPath("$.data.opsRole").value("FINANCE"));

        perform(post("/auth/ops-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", PASSWORD, "confirmPassword", "different!"))))
                .andExpect(status().isBadRequest());
        JsonNode session = data(perform(post("/auth/ops-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", PASSWORD, "confirmPassword", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(session.get("activeRole").asText()).isEqualTo("OPS_ADMIN");
        assertThat(session.get("opsRole").asText()).isEqualTo("FINANCE");
        assertThat(claims(session.get("accessToken").asText()).get(OpsRole.CLAIM)).isEqualTo("FINANCE");

        // the link is single use
        perform(post("/auth/ops-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", PASSWORD, "confirmPassword", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This invite link has already been used - sign in with your email and password"));
        perform(get("/auth/ops-invite").param("token", token)).andExpect(status().isBadRequest());

        // sign in with the email, any case
        JsonNode login = data(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", email.toUpperCase(), "password", PASSWORD, "role", "OPS_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claims(login.get("accessToken").asText()).get(OpsRole.CLAIM)).isEqualTo("FINANCE");
        assertThat(login.get("email").asText()).isEqualTo(email.toLowerCase());
        perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", email, "password", "wrong-one", "role", "OPS_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Incorrect email or password"));

        JsonNode now = data(perform(internal(get("/internal/ops-admins/{id}", adminId), boss)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(now.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(now.get("lastSeenAt").isNull()).isFalse();
        assertThat(history(adminId)).contains(AccountActionType.OPS_ADMIN_INVITED.name(), AccountActionType.OPS_INVITE_ACCEPTED.name());

        // the same email can't be invited twice
        perform(internal(post("/internal/ops-admins/invites"), boss)
                .content(json(Map.of("email", email, "firstName", "N", "lastName", "E", "opsRole", "SUPPORT", "opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(email.toLowerCase() + " is already on the admin team"));
    }

    @Test
    void anExpiredARevokedOrAReplacedInviteLinkIsRefused() throws Exception {
        User boss = superAdmin();
        String adminId = invite(boss, "SUPPORT");
        String first = inviteToken(adminId, 1);

        // resend: the first link dies, the second works
        perform(internal(post("/internal/ops-admins/{id}/invite/resend", adminId), boss).content(json(Map.of("opsUserId", boss.getId()))))
                .andExpect(status().isOk());
        String second = inviteToken(adminId, 2);
        assertThat(invites.get(adminId).get(1).get("resent").asBoolean()).isTrue();
        perform(get("/auth/ops-invite").param("token", first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This invite link is no longer valid - ask a super admin to send a new one"));

        // expired
        OpsInvite current = inviteRepository.findFirstByUserIdOrderByCreatedAtDesc(adminId).orElseThrow();
        current.setExpiresAt(Instant.now().minusSeconds(5));
        inviteRepository.save(current);
        perform(post("/auth/ops-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", second, "password", PASSWORD, "confirmPassword", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This invite link has expired - ask a super admin to send a new one"));
        assertThat(data(perform(internal(get("/internal/ops-admins/{id}", adminId), boss)).andReturn().getResponse()
                .getContentAsString()).get("status").asText()).isEqualTo("INVITE_EXPIRED");

        // revoke needs a pending invite; after a resend it can be revoked
        perform(internal(post("/internal/ops-admins/{id}/invite/revoke", adminId), boss).content(json(Map.of("opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest());
        perform(internal(post("/internal/ops-admins/{id}/invite/resend", adminId), boss).content(json(Map.of("opsUserId", boss.getId()))))
                .andExpect(status().isOk());
        String third = inviteToken(adminId, 3);
        perform(internal(post("/internal/ops-admins/{id}/invite/revoke", adminId), boss)
                .content(json(Map.of("opsUserId", boss.getId(), "reason", "Wrong person"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITE_REVOKED"));
        perform(post("/auth/ops-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", third, "password", PASSWORD, "confirmPassword", PASSWORD))))
                .andExpect(status().isBadRequest());
        perform(get("/auth/ops-invite").param("token", "not-a-real-token")).andExpect(status().isBadRequest());
        assertThat(history(adminId)).contains("OPS_INVITE_RESENT", "OPS_INVITE_REVOKED");
    }

    // ---- who may manage the team -------------------------------------------------------------------------------------

    @Test
    void onlyAnActiveSuperAdminMayManageTheTeam() throws Exception {
        User boss = superAdmin();
        User finance = admin(OpsRole.FINANCE);
        String target = invite(boss, "SUPPORT");
        for (String actor : List.of(finance.getId(), "no-such-admin")) {
            perform(internal(post("/internal/ops-admins/invites"), boss).content(json(Map.of("email", UUID.randomUUID() + "@x.ng",
                    "firstName", "A", "lastName", "B", "opsRole", "SUPPORT", "opsUserId", actor))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Only a super admin can manage the admin team"));
            perform(internal(post("/internal/ops-admins/{id}/role", target), boss)
                    .content(json(Map.of("opsRole", "FINANCE", "opsUserId", actor))))
                    .andExpect(status().isForbidden());
        }
        // a super admin whose console access was removed can't act on a token they still hold
        User removed = superAdmin();
        removed.setOpsDeactivatedAt(Instant.now());
        userRepository.save(removed);
        perform(internal(post("/internal/ops-admins/{id}/deactivate", target), boss)
                .content(json(Map.of("reason", "x", "opsUserId", removed.getId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void nobodyDeactivatesOrDemotesThemselvesAndEveryChangeIsChecked() throws Exception {
        User boss = superAdmin();
        perform(internal(post("/internal/ops-admins/{id}/deactivate", boss.getId()), boss)
                .content(json(Map.of("reason", "Leaving", "opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't deactivate yourself - ask another super admin"));
        perform(internal(post("/internal/ops-admins/{id}/role", boss.getId()), boss)
                .content(json(Map.of("opsRole", "SUPPORT", "opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't change your own ops role - ask another super admin"));

        User support = admin(OpsRole.SUPPORT);
        perform(internal(post("/internal/ops-admins/{id}/role", support.getId()), boss)
                .content(json(Map.of("opsRole", "SUPPORT", "opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest());
        perform(internal(post("/internal/ops-admins/{id}/deactivate", support.getId()), boss)
                .content(json(Map.of("opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give a reason for deactivating this admin"));
        perform(internal(post("/internal/ops-admins/{id}/reactivate", support.getId()), boss)
                .content(json(Map.of("reason", "x", "opsUserId", boss.getId()))))
                .andExpect(status().isBadRequest());
        User rider = userRepository.save(User.builder().phone(phone()).firstName("R").lastName("R")
                .roles(new HashSet<>(Set.of(UserRole.RIDER))).build());
        perform(internal(get("/internal/ops-admins/{id}", rider.getId()), boss)).andExpect(status().isNotFound());
    }

    // ---- deactivation and role changes reach every service at once -------------------------------------------------------

    @Test
    void deactivationEndsTheAdminsSessionsEverywhereAtOnceAndRefusesTheConsoleSignInUntilReactivated() throws Exception {
        User boss = superAdmin();
        User support = admin(OpsRole.SUPPORT);
        String token = login(support);
        perform(get("/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        TokenRevocationRegistry elsewhere = new TokenRevocationRegistry();
        TokenRevocationKafkaListener feed = new TokenRevocationKafkaListener(elsewhere, objectMapper, kafka.getBootstrapServers(), "ops-test");
        feed.start();
        try {
            perform(internal(post("/internal/ops-admins/{id}/deactivate", support.getId()), boss)
                    .content(json(Map.of("reason", "Left the company", "opsUserId", boss.getId(), "opsUserName", "Boss Admin"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DEACTIVATED"))
                    .andExpect(jsonPath("$.data.deactivationReason").value("Left the company"));

            perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(TokenRevocationRegistry.SIGNED_OUT_MESSAGE));
            long issuedAt = JwtUtil.issuedAtMillis(claims(token));
            await(() -> elsewhere.refusal(support.getId(), issuedAt) != null, "the cut-off reaches the other service");

            perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("phone", support.getEmail(), "password", PASSWORD, "role", "OPS_ADMIN"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Your ops console access has been removed. Ask a super admin to restore it."));

            perform(internal(post("/internal/ops-admins/{id}/reactivate", support.getId()), boss)
                    .content(json(Map.of("reason", "Came back", "opsUserId", boss.getId()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
            perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            assertThat(login(support)).isNotBlank();
            assertThat(history(support.getId())).contains("OPS_ADMIN_DEACTIVATED", "OPS_ADMIN_REACTIVATED");
        } finally {
            feed.stop();
        }
    }

    @Test
    void aRoleChangeStopsTheOldTokenAndTheNextRefreshCarriesTheNewRole() throws Exception {
        User boss = superAdmin();
        User finance = admin(OpsRole.FINANCE);
        JsonNode session = loginSession(finance);
        String old = session.get("accessToken").asText();

        perform(internal(post("/internal/ops-admins/{id}/role", finance.getId()), boss)
                .content(json(Map.of("opsRole", "SUPPORT", "reason", "Moved teams", "opsUserId", boss.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opsRole").value("SUPPORT"));
        perform(get("/auth/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());

        JsonNode refreshed = data(perform(post("/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claims(refreshed.get("accessToken").asText()).get(OpsRole.CLAIM)).isEqualTo("SUPPORT");
        perform(get("/auth/me").header("Authorization", "Bearer " + refreshed.get("accessToken").asText())).andExpect(status().isOk());
    }

    @Test
    void aRiderSessionCarriesNoOpsRoleAndAnOpsAdminWithoutOneIsASuperAdmin() throws Exception {
        User legacy = userRepository.save(User.builder().phone(phone()).firstName("Old").lastName("Admin")
                .passwordHash(passwordEncoder.encode(PASSWORD)).roles(new HashSet<>(Set.of(UserRole.OPS_ADMIN, UserRole.RIDER))).build());
        JsonNode ops = data(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", legacy.getPhone(), "password", PASSWORD, "role", "OPS_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claims(ops.get("accessToken").asText()).get(OpsRole.CLAIM)).isEqualTo("SUPER_ADMIN");
        JsonNode rider = data(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", legacy.getPhone(), "password", PASSWORD, "role", "RIDER"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(claims(rider.get("accessToken").asText()).get(OpsRole.CLAIM)).isNull();
        assertThat(rider.get("opsRole").isNull()).isTrue();
    }

    // ---- the identity part of the unified audit feed -------------------------------------------------------------------

    @Test
    void theAuditFeedPagesAccountAndTeamActionsNewestFirstWithFilters() throws Exception {
        User boss = superAdmin();
        String person = userRepository.save(User.builder().phone(phone()).firstName("P").lastName("Q")
                .roles(new HashSet<>(Set.of(UserRole.RIDER))).build()).getId();
        for (int i = 0; i < 5; i++) {
            accountActionService.record(person, AccountActionType.DETAILS_EDITED, boss.getId(), "Boss Admin", "edit " + i,
                    List.of(new org.coryd.identity.dto.RecordAccountActionRequest.Change("firstName", "A" + i, "B" + i)));
        }
        String admin = invite(boss, "VERIFICATION");

        JsonNode first = data(perform(internal(get("/internal/ops/audit"), boss).param("targetId", person).param("size", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode newest = first.get("items").get(0);
        assertThat(newest.get("reason").asText()).isEqualTo("edit 4");
        assertThat(newest.get("category").asText()).isEqualTo("ACCOUNT");
        assertThat(newest.get("targetType").asText()).isEqualTo("ACCOUNT");
        assertThat(objectMapper.readTree(newest.get("before").asText()).get("firstName").asText()).isEqualTo("A4");
        assertThat(objectMapper.readTree(newest.get("after").asText()).get("firstName").asText()).isEqualTo("B4");

        JsonNode last = first.get("items").get(1);
        JsonNode rest = data(perform(internal(get("/internal/ops/audit"), boss).param("targetId", person).param("size", "10")
                .param("beforeAt", last.get("at").asText()).param("beforeId", last.get("id").asText()))
                .andReturn().getResponse().getContentAsString());
        assertThat(rest.get("items")).extracting(n -> n.get("reason").asText()).containsExactly("edit 2", "edit 1", "edit 0");
        assertThat(rest.get("hasMore").asBoolean()).isFalse();

        JsonNode team = data(perform(internal(get("/internal/ops/audit"), boss).param("categories", "TEAM").param("targetId", admin))
                .andReturn().getResponse().getContentAsString());
        assertThat(team.get("items")).extracting(n -> n.get("action").asText()).containsExactly("OPS_ADMIN_INVITED");
        assertThat(team.get("items").get(0).get("targetType").asText()).isEqualTo("ADMIN");

        assertThat(data(perform(internal(get("/internal/ops/audit"), boss).param("targetId", person).param("categories", "TEAM"))
                .andReturn().getResponse().getContentAsString()).get("items")).isEmpty();
        assertThat(data(perform(internal(get("/internal/ops/audit"), boss).param("targetId", person).param("search", "EDIT 3"))
                .andReturn().getResponse().getContentAsString()).get("items")).hasSize(1);
        assertThat(data(perform(internal(get("/internal/ops/audit"), boss).param("actions", "SUSPENDED,REINSTATED").param("targetId", person))
                .andReturn().getResponse().getContentAsString()).get("items")).isEmpty();
        assertThat(data(perform(internal(get("/internal/ops/audit"), boss).param("targetId", person)
                .param("from", Instant.now().plusSeconds(60).toString())).andReturn().getResponse().getContentAsString()).get("items")).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private User superAdmin() {
        return admin(OpsRole.SUPER_ADMIN);
    }

    private User admin(OpsRole role) {
        return userRepository.save(User.builder().email(UUID.randomUUID() + "@coryd.test").firstName("Admin").lastName(role.label())
                .passwordHash(passwordEncoder.encode(PASSWORD)).opsRole(role)
                .roles(new HashSet<>(Set.of(UserRole.OPS_ADMIN))).build());
    }

    private String invite(User boss, String role) throws Exception {
        return data(perform(internal(post("/internal/ops-admins/invites"), boss)
                .content(json(Map.of("email", UUID.randomUUID() + "@invite.test", "firstName", "New", "lastName", "Admin",
                        "opsRole", role, "opsUserId", boss.getId()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("userId").asText();
    }

    private String inviteToken(String userId, int nth) throws InterruptedException {
        await(() -> invites.getOrDefault(userId, List.of()).size() >= nth, "invite email event #" + nth);
        String link = invites.get(userId).get(nth - 1).get("link").asText();
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private String login(User admin) throws Exception {
        return loginSession(admin).get("accessToken").asText();
    }

    private JsonNode loginSession(User admin) throws Exception {
        return data(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", admin.getEmail(), "password", PASSWORD, "role", "OPS_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> history(String userId) throws Exception {
        JsonNode page = data(perform(internal(get("/internal/users/{id}/account-actions", userId), null))
                .andReturn().getResponse().getContentAsString());
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        page.get("content").forEach(n -> actions.add(n.get("action").asText()));
        return actions;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request, User actor) {
        String token = jwtUtil.generateToken(actor == null ? "ops-1" : actor.getId(), null, "Boss", "Admin", UserRole.OPS_ADMIN);
        return request.header(InternalApiKeyFilter.HEADER, KEY).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Claims claims(String token) {
        return jwtUtil.extractAllClaims(token);
    }

    private JsonNode data(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    private static String phone() {
        return "081" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 100_000_000L);
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

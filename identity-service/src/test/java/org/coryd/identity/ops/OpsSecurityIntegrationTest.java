package org.coryd.identity.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.identity.client.DeletionBlockerClient;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.coryd.identity.security.OpsSecurityPolicy;
import org.coryd.identity.security.Totp;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 ops console security against identity's own migrations (real Postgres) and Kafka: two-factor sign-in (TOTP with
 * skew, no replay, recovery codes once, single-use mfa tokens with expiry and an attempt limit), enrollment when it's
 * required, a super admin's reset ending sessions, short ops access tokens and idle/absolute refresh limits on a moved
 * clock - and riders untouched by all of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "services.internal.api-key=" + OpsSecurityIntegrationTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "coryd.token-revocation.refresh-minutes=60"
})
@AutoConfigureMockMvc
@Testcontainers
class OpsSecurityIntegrationTest {

    static final String KEY = "test-internal-key";
    private static final String PASSWORD = "Sup3r-secret!";
    private static final String WRONG = "That code didn't work - try the latest one from your app";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OpsSecurityPolicy policy;
    @MockBean DeletionBlockerClient deletionBlockerClient;

    private Instant clockAt = Instant.now();

    @AfterEach
    void restore() {
        policy.useClock(null);
        policy.overrideMfaRequired(false);
    }

    /** The policy's clock, at a time this test sets. */
    private void at(Instant instant) {
        clockAt = instant;
        policy.useClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void advance(Duration by) {
        at(clockAt.plus(by));
    }

    // ---- riders and drivers -------------------------------------------------------------------------------------------

    @Test
    void ridersAreUntouchedLongAccessTokensThirtyDayRefreshNoSecondFactorAndNoSessionEnd() throws Exception {
        User rider = userRepository.save(User.builder().phone(phone()).firstName("Rita").lastName("Rider")
                .passwordHash(passwordEncoder.encode(PASSWORD)).phoneVerifiedAt(Instant.now())
                .roles(new HashSet<>(Set.of(UserRole.RIDER))).build());
        JsonNode session = ok(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", rider.getPhone(), "password", PASSWORD, "role", "RIDER")))));
        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.has("mfaRequired")).isFalse();
        assertThat(session.get("expiresIn").asLong()).isEqualTo(86_400);
        assertThat(session.get("refreshExpiresIn").asLong()).isGreaterThan(Duration.ofDays(29).toSeconds());
        assertThat(session.get("sessionExpiresAt").isNull()).isTrue();
        Claims claims = jwtUtil.extractAllClaims(session.get("accessToken").asText());
        assertThat(claims.get(JwtUtil.SESSION_EXPIRES_AT_CLAIM)).isNull();

        // long after an ops session would have timed out, the rider's refresh still works
        at(Instant.now().plus(Duration.ofHours(13)));
        perform(post("/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isOk());
    }

    // ---- session lifetime ---------------------------------------------------------------------------------------------

    @Test
    void anOpsAccessTokenLives15MinutesAndTheSessionsEndIsOnIt() throws Exception {
        at(Instant.now());
        User admin = admin(OpsRole.SUPPORT);
        JsonNode session = loginSession(admin);
        assertThat(session.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(session.get("refreshExpiresIn").asLong()).isEqualTo(1800);
        Claims claims = jwtUtil.extractAllClaims(session.get("accessToken").asText());
        assertThat((claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000).isEqualTo(900);
        Instant sessionEnd = Instant.parse(session.get("sessionExpiresAt").asText());
        assertThat(sessionEnd).isEqualTo(clockAt.plus(Duration.ofHours(12)));
        assertThat(((Number) claims.get(JwtUtil.SESSION_EXPIRES_AT_CLAIM)).longValue()).isEqualTo(sessionEnd.toEpochMilli());
    }

    @Test
    void anOpsRefreshTokenDiesAfter30IdleMinutes() throws Exception {
        at(Instant.now());
        JsonNode session = loginSession(admin(OpsRole.FINANCE));
        advance(Duration.ofMinutes(29));
        JsonNode refreshed = refresh(session.get("refreshToken").asText()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::data);
        // each refresh slides the idle window
        advance(Duration.ofMinutes(29));
        JsonNode again = data(refresh(refreshed.get("refreshToken").asText()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        advance(Duration.ofMinutes(31));
        refresh(again.get("refreshToken").asText())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(OpsSecurityPolicy.SESSION_TIMED_OUT));
    }

    @Test
    void anOpsSessionEnds12HoursAfterSignInHoweverActive() throws Exception {
        at(Instant.now());
        Instant signedIn = clockAt;
        JsonNode session = loginSession(admin(OpsRole.VERIFICATION));
        String refreshToken = session.get("refreshToken").asText();
        while (clockAt.plus(Duration.ofMinutes(25)).isBefore(signedIn.plus(Duration.ofHours(12)))) {
            advance(Duration.ofMinutes(25));
            JsonNode next = data(refresh(refreshToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            refreshToken = next.get("refreshToken").asText();
            assertThat(Instant.parse(next.get("sessionExpiresAt").asText())).isEqualTo(signedIn.plus(Duration.ofHours(12)));
            // never past the absolute end
            assertThat(next.get("refreshExpiresIn").asLong())
                    .isLessThanOrEqualTo(Duration.between(clockAt, signedIn.plus(Duration.ofHours(12))).toSeconds());
        }
        at(signedIn.plus(Duration.ofHours(12)).plusSeconds(1));
        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(OpsSecurityPolicy.SESSION_TIMED_OUT));
    }

    // ---- two-factor sign-in -------------------------------------------------------------------------------------------

    @Test
    void enrollThenSignInWithACodeTheMfaTokenIsSingleUseAndACodeCantBeReplayed() throws Exception {
        at(Instant.parse("2026-11-13T10:00:05Z"));
        User admin = admin(OpsRole.FINANCE);
        String secret = enroll(admin);
        User stored = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(stored.getMfaSecret()).as("encrypted at rest").startsWith("v1:").doesNotContain(secret);
        assertThat(stored.getMfaEnabledAt()).isNotNull();

        // two steps on, so the step before now is later than the code that turned it on
        advance(Duration.ofSeconds(60));
        JsonNode challenge = login(admin);
        assertThat(challenge.get("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.has("accessToken")).isFalse();
        assertThat(Instant.parse(challenge.get("mfaTokenExpiresAt").asText())).isEqualTo(clockAt.plus(Duration.ofMinutes(5)));
        String mfaToken = challenge.get("mfaToken").asText();

        verify(mfaToken, "000000".equals(code(secret)) ? "111111" : "000000")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        // a code from the step before still counts (clock drift)
        String previous = Totp.code(secret, Totp.step(clockAt) - 1);
        JsonNode session = ok(verify(mfaToken, previous));
        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.get("opsRole").asText()).isEqualTo("FINANCE");
        assertThat(session.get("email").asText()).isEqualTo(admin.getEmail());
        assertThat(jwtUtil.extractAllClaims(session.get("accessToken").asText()).get(OpsRole.CLAIM)).isEqualTo("FINANCE");

        // single use
        verify(mfaToken, code(secret)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your sign-in has expired - sign in again"));

        // the code just used (and anything older) can't be used again on a fresh sign-in
        String second = login(admin).get("mfaToken").asText();
        verify(second, previous).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        ok(verify(second, code(secret)));
        String third = login(admin).get("mfaToken").asText();
        verify(third, code(secret)).andExpect(status().isUnauthorized());
        advance(Duration.ofSeconds(30));
        ok(verify(third, code(secret)));
    }

    @Test
    void fiveWrongCodesEndTheMfaTokenAndItExpiresAfterFiveMinutes() throws Exception {
        at(Instant.parse("2026-11-13T11:00:05Z"));
        User admin = admin(OpsRole.SUPPORT);
        String secret = enroll(admin);
        advance(Duration.ofSeconds(30));
        String mfaToken = login(admin).get("mfaToken").asText();
        String wrong = code(secret).equals("123456") ? "654321" : "123456";
        for (int i = 1; i <= 4; i++) {
            verify(mfaToken, wrong).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        }
        verify(mfaToken, wrong).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Too many attempts - sign in again"));
        verify(mfaToken, code(secret)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Too many attempts - sign in again"));

        String late = login(admin).get("mfaToken").asText();
        advance(Duration.ofMinutes(5).plusSeconds(1));
        verify(late, code(secret)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your sign-in has expired - sign in again"));
    }

    @Test
    void aRecoveryCodeSignsInOnceAndIsAudited() throws Exception {
        at(Instant.parse("2026-11-13T12:00:05Z"));
        User admin = admin(OpsRole.VERIFICATION);
        JsonNode enrolled = enrollWithCodes(admin);
        List<String> codes = new ArrayList<>();
        enrolled.get("recoveryCodes").forEach(c -> codes.add(c.asText()));
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("[a-z0-9]{4}-[a-z0-9]{4}")).doesNotHaveDuplicates();

        ok(verify(login(admin).get("mfaToken").asText(), codes.get(0).toUpperCase()));
        verify(login(admin).get("mfaToken").asText(), codes.get(0)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG));
        assertThat(history(admin.getId())).contains("RECOVERY_CODE_USED", "MFA_ENABLED");
        assertThat(ok(perform(internal(get("/internal/ops-admins/{id}/mfa", admin.getId()), admin))).get("recoveryCodesLeft").asLong())
                .isEqualTo(9);

        // regenerate: the old ones stop working
        advance(Duration.ofSeconds(30));
        JsonNode fresh = ok(perform(internal(post("/internal/ops-admins/{id}/mfa/recovery-codes", admin.getId()), admin)
                .content(json(Map.of("code", code(secretOf(admin)))))));
        assertThat(fresh.get("recoveryCodes")).hasSize(10);
        verify(login(admin).get("mfaToken").asText(), codes.get(1)).andExpect(status().isUnauthorized());
        assertThat(history(admin.getId())).contains("MFA_RECOVERY_CODES_REGENERATED");
    }

    @Test
    void whenTwoFactorIsRequiredAnAdminWithoutItEnrollsAtSignIn() throws Exception {
        at(Instant.parse("2026-11-13T13:00:05Z"));
        policy.overrideMfaRequired(true);
        User admin = admin(OpsRole.SUPPORT);
        JsonNode step = login(admin);
        assertThat(step.get("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(step.has("accessToken")).isFalse();
        String enrollToken = step.get("enrollToken").asText();

        JsonNode setup = ok(perform(post("/auth/mfa/enroll/setup").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollToken", enrollToken)))));
        String secret = setup.get("secret").asText();
        assertThat(setup.get("issuer").asText()).isEqualTo("CoRyd Ops");
        assertThat(setup.get("account").asText()).isEqualTo(admin.getEmail());
        assertThat(setup.get("otpauthUrl").asText()).startsWith("otpauth://totp/CoRyd%20Ops:").contains("secret=" + secret)
                .contains("issuer=CoRyd%20Ops").contains("digits=6").contains("period=30");
        // asking again (a reload) shows the same secret
        assertThat(ok(perform(post("/auth/mfa/enroll/setup").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollToken", enrollToken))))).get("secret").asText()).isEqualTo(secret);

        String wrong = code(secret).equals("123456") ? "654321" : "123456";
        perform(post("/auth/mfa/enroll/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollToken", enrollToken, "code", wrong))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        JsonNode done = ok(perform(post("/auth/mfa/enroll/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollToken", enrollToken, "code", code(secret))))));
        assertThat(done.get("session").get("accessToken").asText()).isNotBlank();
        assertThat(done.get("session").get("opsRole").asText()).isEqualTo("SUPPORT");
        assertThat(done.get("recoveryCodes")).hasSize(10);
        perform(post("/auth/mfa/enroll/setup").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("enrollToken", enrollToken))))
                .andExpect(status().isUnauthorized());

        // from now on, a code at sign-in; and it can't be turned off while required
        assertThat(login(admin).get("mfaRequired").asBoolean()).isTrue();
        advance(Duration.ofSeconds(30));
        perform(internal(post("/internal/ops-admins/{id}/mfa/disable", admin.getId()), admin)
                .content(json(Map.of("code", code(secret), "reason", "New phone"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Two-factor sign-in is required for the ops console - it can't be turned off"));
        assertThat(history(admin.getId())).contains("MFA_ENABLED");
    }

    @Test
    void turningTwoFactorOffNeedsACodeAndAReason() throws Exception {
        at(Instant.parse("2026-11-13T14:00:05Z"));
        User admin = admin(OpsRole.FINANCE);
        String secret = enroll(admin);
        advance(Duration.ofSeconds(30));
        perform(internal(post("/internal/ops-admins/{id}/mfa/disable", admin.getId()), admin)
                .content(json(Map.of("code", code(secret)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Give a reason for turning off two-factor sign-in"));
        String wrong = code(secret).equals("123456") ? "654321" : "123456";
        perform(internal(post("/internal/ops-admins/{id}/mfa/disable", admin.getId()), admin)
                .content(json(Map.of("code", wrong, "reason", "New phone"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(WRONG));
        JsonNode off = ok(perform(internal(post("/internal/ops-admins/{id}/mfa/disable", admin.getId()), admin)
                .content(json(Map.of("code", code(secret), "reason", "New phone")))));
        assertThat(off.get("mfaEnabled").asBoolean()).isFalse();
        assertThat(loginSession(admin).get("accessToken").asText()).isNotBlank();
        assertThat(history(admin.getId())).contains("MFA_DISABLED");
    }

    @Test
    void aSuperAdminsResetEndsEverySessionAndTheAdminSignsInWithoutItAfterwards() throws Exception {
        at(Instant.parse("2026-11-13T15:00:05Z"));
        User boss = admin(OpsRole.SUPER_ADMIN);
        User admin = admin(OpsRole.SUPPORT);
        String secret = enroll(admin);
        advance(Duration.ofSeconds(30));
        JsonNode session = ok(verify(login(admin).get("mfaToken").asText(), code(secret)));
        String accessToken = session.get("accessToken").asText();
        perform(get("/auth/me").header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        JsonNode listed = ok(perform(internal(get("/internal/ops-admins/{id}", admin.getId()), boss)));
        assertThat(listed.get("mfaEnabled").asBoolean()).isTrue();

        // only an active super admin, never on themselves, always with a reason
        User finance = admin(OpsRole.FINANCE);
        perform(internal(post("/internal/ops-admins/{id}/mfa/reset", admin.getId()), finance)
                .content(json(Map.of("opsUserId", finance.getId(), "reason", "Lost phone")))).andExpect(status().isForbidden());
        perform(internal(post("/internal/ops-admins/{id}/mfa/reset", boss.getId()), boss)
                .content(json(Map.of("opsUserId", boss.getId(), "reason", "Lost phone")))).andExpect(status().isBadRequest());
        perform(internal(post("/internal/ops-admins/{id}/mfa/reset", admin.getId()), boss)
                .content(json(Map.of("opsUserId", boss.getId())))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give a reason for resetting two-factor sign-in"));

        Thread.sleep(5);
        JsonNode reset = ok(perform(internal(post("/internal/ops-admins/{id}/mfa/reset", admin.getId()), boss)
                .content(json(Map.of("opsUserId", boss.getId(), "opsUserName", "Boss Admin", "reason", "Lost phone")))));
        assertThat(reset.get("mfaEnabled").asBoolean()).isFalse();

        perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
        refresh(session.get("refreshToken").asText()).andExpect(status().is4xxClientError());
        assertThat(history(admin.getId())).contains("MFA_RESET");
        JsonNode audit = ok(perform(internal(get("/internal/ops/audit"), boss).param("targetId", admin.getId())));
        List<String> categories = new ArrayList<>();
        audit.get("items").forEach(i -> {
            if (i.get("action").asText().startsWith("MFA_")) {
                categories.add(i.get("category").asText());
            }
        });
        assertThat(categories).isNotEmpty().containsOnly("TEAM");

        // policy off: a plain session again; on: enrollment
        assertThat(loginSession(admin).get("accessToken").asText()).isNotBlank();
        policy.overrideMfaRequired(true);
        assertThat(login(admin).get("mfaEnrollmentRequired").asBoolean()).isTrue();
    }

    @Test
    void aRiderSessionCantSwitchIntoTheConsoleWhenTwoFactorApplies() throws Exception {
        at(Instant.parse("2026-11-13T16:00:05Z"));
        User both = userRepository.save(User.builder().phone(phone()).email(UUID.randomUUID() + "@coryd.test").firstName("Two")
                .lastName("Hats").passwordHash(passwordEncoder.encode(PASSWORD)).phoneVerifiedAt(Instant.now()).opsRole(OpsRole.SUPPORT)
                .roles(new HashSet<>(Set.of(UserRole.RIDER, UserRole.OPS_ADMIN))).build());
        enroll(both);
        String riderToken = ok(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", both.getPhone(), "password", PASSWORD, "role", "RIDER"))))).get("accessToken").asText();
        perform(post("/auth/roles/switch").header("Authorization", "Bearer " + riderToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("role", "OPS_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sign in to the ops console with your email, password and two-factor code"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Sets two-factor up through the signed-in path and returns the secret. */
    private String enroll(User admin) throws Exception {
        enrollWithCodes(admin);
        return secretOf(admin);
    }

    private final Map<String, String> secrets = new java.util.concurrent.ConcurrentHashMap<>();

    private String secretOf(User admin) {
        return secrets.get(admin.getId());
    }

    private JsonNode enrollWithCodes(User admin) throws Exception {
        JsonNode setup = ok(perform(internal(post("/internal/ops-admins/{id}/mfa/setup", admin.getId()), admin)));
        String secret = setup.get("secret").asText();
        secrets.put(admin.getId(), secret);
        return ok(perform(internal(post("/internal/ops-admins/{id}/mfa/enable", admin.getId()), admin)
                .content(json(Map.of("code", code(secret))))));
    }

    private String code(String secret) {
        return Totp.code(secret, Totp.step(clockAt));
    }

    private User admin(OpsRole role) {
        return userRepository.save(User.builder().email(UUID.randomUUID() + "@coryd.test").firstName("Admin").lastName(role.label())
                .passwordHash(passwordEncoder.encode(PASSWORD)).opsRole(role)
                .roles(new HashSet<>(Set.of(UserRole.OPS_ADMIN))).build());
    }

    private JsonNode login(User admin) throws Exception {
        return ok(perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("phone", admin.getEmail(), "password", PASSWORD, "role", "OPS_ADMIN")))));
    }

    private JsonNode loginSession(User admin) throws Exception {
        JsonNode session = login(admin);
        assertThat(session.has("accessToken")).as("a session, not a second step").isTrue();
        return session;
    }

    private ResultActions verify(String mfaToken, String code) throws Exception {
        return perform(post("/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("mfaToken", mfaToken, "code", code))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return perform(post("/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refreshToken))));
    }

    private List<String> history(String userId) throws Exception {
        JsonNode page = ok(perform(internal(get("/internal/users/{id}/account-actions", userId), null)));
        List<String> actions = new ArrayList<>();
        page.get("content").forEach(n -> actions.add(n.get("action").asText()));
        return actions;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private JsonNode ok(ResultActions result) throws Exception {
        return data(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request, User actor) {
        String token = jwtUtil.generateToken(actor == null ? "ops-1" : actor.getId(), null, "Boss", "Admin", UserRole.OPS_ADMIN);
        return request.header(InternalApiKeyFilter.HEADER, KEY).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(String body) {
        try {
            return objectMapper.readTree(body).get("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String phone() {
        return "081" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 100_000_000L);
    }
}

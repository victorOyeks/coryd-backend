package org.coryd.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6A in ops-service against stand-in identity/profile/trip/payment services on a real port: ops roles allowed and
 * refused per endpoint group (before anything downstream is called), GET /me, the admin team relay and its own refusals,
 * immediate revocation of a deactivated admin's token, and the unified audit merge - ordering, cursor paging, filters,
 * enrichment, a failing source, and the CSV export.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=" + OpsTeamRolesAuditApiTest.SECRET
})
@AutoConfigureMockMvc
class OpsTeamRolesAuditApiTest {

    static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789";

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();
    /** What each stand-in source holds, already newest first (time desc, id desc - what its database would return). */
    private static final Map<String, List<OpsAuditEntry>> sources = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String RIDER = "a11ce000-2222-0000-0000-000000000001";
    private static final String ROUTE = "2f2f53aa-0000-0000-0000-000000000001";

    private record Reply(int status, String body) {
    }

    private record Request(String method, String path, Map<String, String> query, String body) {
    }

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + UPSTREAM.getAddress().getPort();
        registry.add("services.profile.url", () -> base + "/profile");
        registry.add("services.trip.url", () -> base + "/trip");
        registry.add("services.identity.url", () -> base + "/identity");
        registry.add("services.payment.url", () -> base + "/payment");
        registry.add("services.notification.url", () -> base + "/notification");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired TokenRevocationRegistry registry;

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
        sources.clear();
        registry.clear();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    // ---- ops roles ----------------------------------------------------------------------------------------------------

    /** A fresh request each time - a reused builder keeps every Authorization header added to it. */
    private record Call(java.util.function.Supplier<MockHttpServletRequestBuilder> request, List<OpsRole> allowed) {
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void eachOpsRoleReachesItsOwnEndpointGroupsAndIsRefusedTheRestBeforeAnythingIsCalled() throws Exception {
        OpsRole S = OpsRole.SUPER_ADMIN, V = OpsRole.VERIFICATION, F = OpsRole.FINANCE, P = OpsRole.SUPPORT;
        List<Call> calls = List.of(
                new Call(() -> get("/money/transactions"), List.of(S, F, P)),
                new Call(() -> postJson("/money/bookings/b-1/refunds", "{\"reason\":\"x\"}"), List.of(S, F)),
                new Call(() -> postJson("/money/approvals/r-1/approve", "{}"), List.of(S, F)),
                new Call(() -> postJson("/money/drivers/d-1/payout-account/reveal", "{\"reason\":\"x\"}"), List.of(S, F)),
                new Call(() -> get("/trips"), List.of(S, F, P)),
                new Call(() -> postJson("/trips/" + ROUTE + "/cancel", "{\"reason\":\"x\"}"), List.of(S, P)),
                new Call(() -> postJson("/bookings/b-1/cancel", "{\"reason\":\"x\"}"), List.of(S, P)),
                new Call(() -> postJson("/users/" + RIDER + "/suspend", "{\"reason\":\"x\"}"), List.of(S, P)),
                new Call(() -> delete("/users/" + RIDER), List.of(S)),
                new Call(() -> get("/riders"), List.of(S, V, F, P)),
                new Call(() -> get("/drivers/verification/queue"), List.of(S, V)),
                new Call(() -> postJson("/identity-verification/people/" + RIDER + "/approve", "{\"note\":\"x\"}"), List.of(S, V)),
                new Call(() -> get("/document-expiry"), List.of(S, V)),
                new Call(() -> get("/sos"), List.of(S, P)),
                new Call(() -> postJson("/sos/s-1/acknowledge", "{}"), List.of(S, P)),
                new Call(() -> postJson("/ratings/r-1/hide", "{\"reason\":\"x\"}"), List.of(S, P)),
                new Call(() -> get("/team"), List.of(S)),
                new Call(() -> get("/audit"), List.of(S)),
                new Call(() -> get("/team/roles"), List.of(S, V, F, P)));
        for (Call call : calls) {
            for (OpsRole role : OpsRole.values()) {
                requests.clear();
                int status = mockMvc.perform(call.request().get().header("Authorization", bearer("ops-" + role, role)))
                        .andReturn().getResponse().getStatus();
                var built = call.request().get().buildRequest(new org.springframework.mock.web.MockServletContext());
                String what = role + " " + built.getMethod() + " " + built.getRequestURI();
                if (call.allowed().contains(role)) {
                    assertThat(status).as(what).isNotEqualTo(403).isNotEqualTo(401);
                } else {
                    assertThat(status).as(what).isEqualTo(403);
                    assertThat(requests).as(what + " never reaches another service").isEmpty();
                }
            }
        }
    }

    @Test
    void theRefusalNamesTheRoleAndWhatItCantDo() throws Exception {
        mockMvc.perform(postJson("/trips/" + ROUTE + "/cancel", "{\"reason\":\"x\"}").header("Authorization", bearer("f-1", OpsRole.FINANCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (FINANCE) can't cancel or resolve trips and bookings"));
        mockMvc.perform(get("/team").header("Authorization", bearer("s-1", OpsRole.SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (SUPPORT) can't manage the admin team"));
        // an endpoint no rule covers is for super admins only
        mockMvc.perform(get("/something-new").header("Authorization", bearer("v-1", OpsRole.VERIFICATION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (VERIFICATION) can't use this part of the console - it's for super admins"));
        mockMvc.perform(get("/something-new").header("Authorization", bearer("s-1", OpsRole.SUPER_ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTokenWithoutAnOpsRoleIsASuperAdminAndAnUnknownRoleGetsNothing() throws Exception {
        replies.put("/identity/internal/ops-admins", new Reply(200, "{\"data\":[]}"));
        String legacy = "Bearer " + jwtUtil.generateToken("old-admin", null, "Old", "Admin", UserRole.OPS_ADMIN);
        mockMvc.perform(get("/team").header("Authorization", legacy)).andExpect(status().isOk());

        String unknown = "Bearer " + Jwts.builder().subject("odd-admin").claim("role", "OPS_ADMIN").claim(OpsRole.CLAIM, "JANITOR")
                .claim("firstName", "Odd").issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        mockMvc.perform(get("/riders").header("Authorization", unknown))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (JANITOR) isn't recognised - sign out and sign in again"));
        // riders and drivers are still refused outright by SecurityConfig
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + jwtUtil.generateToken("r-1", null, "R", "R", UserRole.RIDER)))
                .andExpect(status().isForbidden());
    }

    // ---- /me ------------------------------------------------------------------------------------------------------------

    @Test
    void meSaysWhoIsSignedInTheirRoleAndExactlyWhatTheyMayDo() throws Exception {
        replies.put("/identity/internal/ops-admins/fin-1", new Reply(200, "{\"data\":{\"userId\":\"fin-1\",\"email\":\"fola@coryd.ng\"}}"));
        JsonNode me = data(mockMvc.perform(get("/me").header("Authorization", bearer("fin-1", OpsRole.FINANCE)))
                .andExpect(status().isOk()).andReturn());
        assertThat(me.get("name").asText()).isEqualTo("Ada Admin");
        assertThat(me.get("email").asText()).isEqualTo("fola@coryd.ng");
        assertThat(me.get("opsRole").asText()).isEqualTo("FINANCE");
        assertThat(me.get("opsRoleLabel").asText()).isEqualTo("Finance");
        List<String> permissions = new ArrayList<>();
        me.get("permissions").forEach(p -> permissions.add(p.asText()));
        assertThat(permissions).contains("MONEY_VIEW", "MONEY_MANAGE", "TRIPS_VIEW", "DIRECTORY_VIEW")
                .doesNotContain("TEAM_MANAGE", "AUDIT_VIEW", "TRIPS_MANAGE", "ACCOUNT_MANAGE");
        assertThat(requests).extracting(Request::path).contains("/identity/internal/ops-admins/fin-1/seen");

        // identity down: still answers from the token
        replies.put("/identity/internal/ops-admins/sup-1", new Reply(503, "{\"message\":\"down\"}"));
        mockMvc.perform(get("/me").header("Authorization", bearer("sup-1", OpsRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opsRole").value("SUPPORT"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    // ---- the admin team -------------------------------------------------------------------------------------------------

    @Test
    void teamActionsGoToIdentityWithTheActingAdminAndTheirOwnRefusalsComeBack() throws Exception {
        String boss = bearer("boss-1", OpsRole.SUPER_ADMIN);
        replies.put("/identity/internal/ops-admins", new Reply(200,
                "{\"data\":[{\"userId\":\"boss-1\",\"status\":\"ACTIVE\"},{\"userId\":\"a-2\",\"status\":\"INVITED\"}]}"));
        mockMvc.perform(get("/team").header("Authorization", boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].you").value(true))
                .andExpect(jsonPath("$.data[1].you").value(false));

        replies.put("/identity/internal/ops-admins/invites", new Reply(200, "{\"data\":{\"userId\":\"new-1\",\"status\":\"INVITED\"}}"));
        mockMvc.perform(postJson("/team/invites", "{\"email\":\"Ngozi@CoRyd.ng\",\"firstName\":\"Ngozi\",\"lastName\":\"Eze\",\"opsRole\":\"FINANCE\"}")
                        .header("Authorization", boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Invite sent"));
        JsonNode sent = MAPPER.readTree(requests.getLast().body());
        assertThat(sent.get("email").asText()).isEqualTo("Ngozi@CoRyd.ng");
        assertThat(sent.get("opsRole").asText()).isEqualTo("FINANCE");
        assertThat(sent.get("opsUserId").asText()).isEqualTo("boss-1");
        assertThat(sent.get("opsUserName").asText()).isEqualTo("Ada Admin");

        requests.clear();
        mockMvc.perform(postJson("/team/invites", "{\"email\":\"not-an-email\",\"firstName\":\"N\",\"lastName\":\"E\",\"opsRole\":\"FINANCE\"}")
                .header("Authorization", boss)).andExpect(status().isBadRequest());
        mockMvc.perform(postJson("/team/invites", "{\"email\":\"a@b.ng\",\"firstName\":\"N\",\"lastName\":\"E\",\"opsRole\":\"BOSS\"}")
                .header("Authorization", boss)).andExpect(status().isBadRequest());
        mockMvc.perform(postJson("/team/boss-1/deactivate", "{\"reason\":\"Leaving\"}").header("Authorization", boss))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't deactivate yourself - ask another super admin"));
        mockMvc.perform(postJson("/team/boss-1/role", "{\"opsRole\":\"SUPPORT\"}").header("Authorization", boss))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't change your own ops role - ask another super admin"));
        mockMvc.perform(postJson("/team/a-2/deactivate", "{}").header("Authorization", boss))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give a reason for deactivating this admin"));
        assertThat(requests).as("refused here, never sent").isEmpty();

        replies.put("/identity/internal/ops-admins/a-2/role", new Reply(400,
                "{\"success\":false,\"message\":\"Bola Ade is the last super admin - make someone else a super admin first\"}"));
        mockMvc.perform(postJson("/team/a-2/role", "{\"opsRole\":\"SUPPORT\",\"reason\":\"Moving\"}").header("Authorization", boss))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bola Ade is the last super admin - make someone else a super admin first"));
        JsonNode role = MAPPER.readTree(requests.getLast().body());
        assertThat(role.get("opsRole").asText()).isEqualTo("SUPPORT");
        assertThat(role.get("reason").asText()).isEqualTo("Moving");

        mockMvc.perform(postJson("/team/a-2/invite/resend", "").header("Authorization", boss)).andExpect(status().isOk());
        assertThat(requests.getLast().path()).isEqualTo("/identity/internal/ops-admins/a-2/invite/resend");
        mockMvc.perform(postJson("/team/a-2/deactivate", "{\"reason\":\"Left\"}").header("Authorization", boss)).andExpect(status().isOk());
        assertThat(requests.getLast().path()).isEqualTo("/identity/internal/ops-admins/a-2/deactivate");
    }

    @Test
    void aDeactivatedAdminsTokenIsRefusedTheMomentTheCutOffArrives() throws Exception {
        String token = bearer("sup-9", OpsRole.SUPPORT);
        mockMvc.perform(get("/team/roles").header("Authorization", token)).andExpect(status().isOk());
        registry.apply(UserTokensRevokedEvent.builder().userId("sup-9").tokensValidAfter(Instant.now().plusMillis(5)).suspended(false)
                .reason("OPS_ADMIN_DEACTIVATED").stateAt(Instant.now()).build());
        mockMvc.perform(get("/team/roles").header("Authorization", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(TokenRevocationRegistry.SIGNED_OUT_MESSAGE));
    }

    // ---- the unified audit log ------------------------------------------------------------------------------------------

    private static final Instant T = Instant.parse("2026-11-12T10:00:00Z");

    private static OpsAuditEntry entry(String id, int secondsAfter, OpsAuditCategory category, String action, String targetType,
                                       String targetId) {
        return new OpsAuditEntry(id, T.plusSeconds(secondsAfter), null, category, action, "boss-1", null, targetType, targetId, null,
                "because " + id, "{\"status\":\"A\"}", "{\"status\":\"B\"}", "MONEY".equals(category.name()) ? new BigDecimal("500.00") : null,
                "TRIPS".equals(category.name()) ? ROUTE : null);
    }

    private void seedSources() {
        sources.put("identity", sorted(
                entry("i1", 1, OpsAuditCategory.ACCOUNT, "SUSPENDED", "ACCOUNT", RIDER),
                entry("i2", 5, OpsAuditCategory.TEAM, "OPS_ROLE_CHANGED", "ADMIN", "admin-2"),
                entry("i3", 9, OpsAuditCategory.ACCOUNT, "REINSTATED", "ACCOUNT", RIDER)));
        sources.put("profile", sorted(
                entry("p1", 2, OpsAuditCategory.VERIFICATION, "APPROVED", "VERIFICATION", RIDER),
                entry("p2", 5, OpsAuditCategory.VERIFICATION, "REJECTED", "VERIFICATION", RIDER)));
        sources.put("trip", sorted(
                entry("t1", 3, OpsAuditCategory.TRIPS, "CANCEL_TRIP", "ROUTE", ROUTE),
                entry("t2", 5, OpsAuditCategory.SAFETY, "SOS_RESOLVED", "SOS", "sos-1"),
                entry("t3", 8, OpsAuditCategory.TRIPS, "CANCEL_BOOKING", "BOOKING", "booking-1")));
        sources.put("payment", sorted(
                entry("m1", 4, OpsAuditCategory.MONEY, "REFUND_EXECUTED", "BOOKING", "booking-1"),
                entry("m2", 7, OpsAuditCategory.MONEY, "REQUEST_APPROVED", "MONEY_REQUEST", "req-1")));
        replies.put("/identity/internal/users/contacts", new Reply(200, "{\"data\":[{\"userId\":\"" + RIDER
                + "\",\"fullName\":\"Amaka Obi\",\"phone\":\"0801\"},{\"userId\":\"boss-1\",\"fullName\":\"Ada Admin\"}]}"));
    }

    private static List<OpsAuditEntry> sorted(OpsAuditEntry... entries) {
        return Arrays.stream(entries).sorted(Comparator.comparing(OpsAuditEntry::at).thenComparing(OpsAuditEntry::id).reversed()).toList();
    }

    @Test
    void theFeedMergesEverySourceNewestFirstAndPagesThroughThemWithoutGapsOrRepeats() throws Exception {
        seedSources();
        String boss = bearer("boss-1", OpsRole.SUPER_ADMIN);
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = get("/audit").param("size", "3").header("Authorization", boss);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
            page.get("items").forEach(i -> ids.add(i.get("id").asText()));
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asText() : null;
            assertThat(page.get("items").size()).isLessThanOrEqualTo(3);
            pages++;
        } while (cursor != null && pages < 10);
        // time desc; the tie at +5s goes by source name (IDENTITY, PROFILE, TRIP)
        assertThat(ids).containsExactly("IDENTITY:i3", "TRIP:t3", "PAYMENT:m2", "IDENTITY:i2", "PROFILE:p2", "TRIP:t2",
                "PAYMENT:m1", "TRIP:t1", "PROFILE:p1", "IDENTITY:i1");
        assertThat(pages).isEqualTo(4);

        JsonNode first = data(mockMvc.perform(get("/audit").param("size", "50").header("Authorization", boss)).andReturn()).get("items");
        JsonNode reinstated = first.get(0);
        assertThat(reinstated.get("target").get("name").asText()).isEqualTo("Amaka Obi");
        assertThat(reinstated.get("actor").get("name").asText()).as("filled in from identity").isEqualTo("Ada Admin");
        assertThat(reinstated.get("before").get("status").asText()).isEqualTo("A");
        JsonNode cancel = first.get(7);
        assertThat(cancel.get("target").get("ref").asText()).isEqualTo("T-2F2F53");
        assertThat(cancel.get("relatedRef").asText()).isEqualTo("T-2F2F53");
        assertThat(first.get(6).get("amount").decimalValue()).isEqualByComparingTo("500");
        assertThat(data(mockMvc.perform(get("/audit").param("size", "50").header("Authorization", boss)).andReturn()).get("hasMore").asBoolean())
                .isFalse();
    }

    @Test
    void filtersReachOnlyTheSourcesThatCanMatchAndLagosDaysBecomeInstants() throws Exception {
        seedSources();
        String boss = bearer("boss-1", OpsRole.SUPER_ADMIN);

        JsonNode money = data(mockMvc.perform(get("/audit").param("category", "MONEY").header("Authorization", boss)).andReturn());
        assertThat(money.get("items")).extracting(n -> n.get("id").asText()).containsExactly("PAYMENT:m2", "PAYMENT:m1");
        assertThat(auditCalls()).containsOnlyKeys("payment");

        requests.clear();
        JsonNode bookings = data(mockMvc.perform(get("/audit").param("targetType", "BOOKING").header("Authorization", boss)).andReturn());
        assertThat(bookings.get("items")).extracting(n -> n.get("id").asText()).containsExactly("TRIP:t3", "PAYMENT:m1");
        assertThat(auditCalls()).containsOnlyKeys("trip", "payment");

        requests.clear();
        mockMvc.perform(get("/audit").param("from", "2026-11-10").param("to", "2026-11-12").param("actorId", "boss-1")
                .param("action", "SUSPENDED", "REINSTATED").param("targetId", RIDER).param("search", "t-2f2f53")
                .header("Authorization", boss)).andExpect(status().isOk());
        Map<String, String> sent = auditCalls().get("identity");
        assertThat(sent).containsEntry("from", "2026-11-09T23:00:00Z").containsEntry("to", "2026-11-12T23:00:00Z")
                .containsEntry("actorId", "boss-1").containsEntry("actions", "SUSPENDED,REINSTATED").containsEntry("targetId", RIDER)
                .containsEntry("search", "2f2f53");

        mockMvc.perform(get("/audit").param("from", "2026-11-12").param("to", "2026-11-10").header("Authorization", boss))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit").param("targetType", "SPACESHIP").header("Authorization", boss))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit").param("category", "WEATHER").header("Authorization", boss))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit").param("cursor", "%%%not-a-cursor").header("Authorization", boss))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSourceThatFailsFailsThePageInsteadOfHidingItsRows() throws Exception {
        seedSources();
        replies.put("/trip/internal/ops/audit", new Reply(503, "{\"message\":\"trip-service is restarting\"}"));
        mockMvc.perform(get("/audit").header("Authorization", bearer("boss-1", OpsRole.SUPER_ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("trip-service is restarting"));
    }

    @Test
    void theCsvExportIsTheFilteredFeedBoundedAndSaysWhenItWasCutOff() throws Exception {
        seedSources();
        String boss = bearer("boss-1", OpsRole.SUPER_ADMIN);
        MvcResult all = mockMvc.perform(get("/audit/export").param("category", "TRIPS", "SAFETY").header("Authorization", boss))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Audit-Truncated", "false"))
                .andExpect(header().string("X-Audit-Rows", "3"))
                .andReturn();
        assertThat(all.getResponse().getContentType()).startsWith("text/csv");
        assertThat(all.getResponse().getHeader("Content-Disposition")).startsWith("attachment; filename=\"coryd-audit-");
        String[] lines = all.getResponse().getContentAsString(StandardCharsets.UTF_8).replace("﻿", "").split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).startsWith("Time (Lagos),Category,Action,Actor");
        assertThat(lines[1]).startsWith("2026-11-12 11:00:08,TRIPS,CANCEL_BOOKING,Ada Admin,boss-1,BOOKING,booking-1,B-BOOKIN");
        assertThat(lines[1]).contains("\"{\"\"status\"\":\"\"A\"\"}\"");

        mockMvc.perform(get("/audit/export").param("limit", "4").header("Authorization", boss))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Audit-Truncated", "true"))
                .andExpect(header().string("X-Audit-Rows", "4"));
        mockMvc.perform(get("/audit/export").header("Authorization", bearer("f-1", OpsRole.FINANCE)))
                .andExpect(status().isForbidden());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    /** Query parameters of the last audit call to each source, by source. */
    private static Map<String, Map<String, String>> auditCalls() {
        Map<String, Map<String, String>> calls = new LinkedHashMap<>();
        for (Request r : requests) {
            if (r.path().endsWith("/internal/ops/audit")) {
                calls.put(r.path().substring(1, r.path().indexOf('/', 1)), r.query());
            }
        }
        return calls;
    }

    private String bearer(String userId, OpsRole role) {
        return "Bearer " + jwtUtil.generateToken(userId, null, "Ada", "Admin", UserRole.OPS_ADMIN, role);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsTeamRolesAuditApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        if (!path.endsWith("/token-revocations")) {
            requests.add(new Request(exchange.getRequestMethod(), path, query, body));
        }
        Reply reply = replies.get(path);
        if (reply == null && path.endsWith("/internal/ops/audit")) {
            reply = new Reply(200, auditPage(path.substring(1, path.indexOf('/', 1)), query));
        }
        if (reply == null) {
            reply = new Reply(200, "{\"success\":true,\"data\":null}");
        }
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    /** What a source would answer: its rows after the cursor that pass the category / target type / action filters. */
    private static String auditPage(String source, Map<String, String> q) {
        List<OpsAuditEntry> rows = sources.getOrDefault(source, List.of());
        Instant beforeAt = q.containsKey("beforeAt") ? Instant.parse(q.get("beforeAt")) : null;
        String beforeId = q.get("beforeId");
        List<String> categories = q.containsKey("categories") ? List.of(q.get("categories").split(",")) : null;
        List<String> types = q.containsKey("targetTypes") ? List.of(q.get("targetTypes").split(",")) : null;
        int size = Integer.parseInt(q.getOrDefault("size", "50"));
        List<OpsAuditEntry> after = rows.stream()
                .filter(e -> beforeAt == null || e.at().isBefore(beforeAt) || (e.at().equals(beforeAt) && e.id().compareTo(beforeId) < 0))
                .filter(e -> categories == null || categories.contains(e.category().name()))
                .filter(e -> types == null || types.contains(e.targetType()))
                .collect(Collectors.toList());
        try {
            return MAPPER.writeValueAsString(Map.of("data", Map.of("items", after.stream().limit(size).toList(), "hasMore", after.size() > size)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

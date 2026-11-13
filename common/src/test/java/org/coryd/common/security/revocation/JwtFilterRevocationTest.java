package org.coryd.common.security.revocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.UserRole;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.JwtFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a revoked token gets from any service: no authentication, and a 401 that says why. */
class JwtFilterRevocationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TokenRevocationRegistry registry = new TokenRevocationRegistry();
    private final JwtUtil jwtUtil = jwtUtil();
    private final JwtFilter filter = new JwtFilter(jwtUtil, registry);

    private JwtUtil jwtUtil() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "a-test-secret-that-is-long-enough-for-hmac-sha-256-0123456789");
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(jwt, "buildParser");
        jwt.setRevocations(registry);
        return jwt;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTokenIssuedBeforeTheCutOffIsRefusedWithTheReasonAndALaterOneWorks() throws Exception {
        String old = jwtUtil.generateToken("user-1", "08011112222", "Ada", "Obi", UserRole.RIDER);
        Thread.sleep(5);
        registry.apply("user-1", Instant.now(), false, Instant.now());
        Thread.sleep(5);
        String fresh = jwtUtil.generateToken("user-1", "08011112222", "Ada", "Obi", UserRole.RIDER);

        MockHttpServletRequest request = request(old);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtFilter.REFUSAL_ATTRIBUTE)).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
        assertThat(jwtUtil.isTokenValid(old)).as("WebSocket CONNECT refuses it too").isFalse();

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityBeans().authenticationEntryPoint(objectMapper)
                .commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("message").asText())
                .isEqualTo("Your session has ended. Please sign in again.");

        SecurityContextHolder.clearContext();
        filter.doFilter(request(fresh), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(((CoRydUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId()).isEqualTo("user-1");
        assertThat(jwtUtil.isTokenValid(fresh)).isTrue();
    }

    @Test
    void aSuspendedAccountGetsTheSuspensionMessageAndOtherFailuresKeepTheirs() throws Exception {
        String token = jwtUtil.generateToken("user-1", null, "Ada", "Obi", UserRole.DRIVER);
        registry.apply("user-1", Instant.now(), true, Instant.now());

        MockHttpServletRequest request = request(token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityBeans().authenticationEntryPoint(objectMapper).commence(request, response,
                new InsufficientAuthenticationException("x"));
        assertThat(objectMapper.readTree(response.getContentAsString()).get("message").asText())
                .isEqualTo("This account has been suspended. Contact CoRyd support.");

        MockHttpServletResponse plain = new MockHttpServletResponse();
        new SecurityBeans().authenticationEntryPoint(objectMapper).commence(new MockHttpServletRequest(), plain,
                new InsufficientAuthenticationException("Full authentication is required"));
        assertThat(objectMapper.readTree(plain.getContentAsString()).get("message").asText())
                .isEqualTo("Unauthorized: Full authentication is required");
    }

    @Test
    void tokensCarryTheirIssueTimeToTheMillisecondAndOlderOnesFallBackToIat() {
        long before = System.currentTimeMillis();
        Claims claims = jwtUtil.extractAllClaims(jwtUtil.generateToken("user-1", null, "Ada", "Obi", UserRole.RIDER));
        assertThat(JwtUtil.issuedAtMillis(claims)).isBetween(before, System.currentTimeMillis());

        Claims legacy = io.jsonwebtoken.Jwts.claims().subject("user-1").issuedAt(claims.getIssuedAt()).build();
        assertThat(JwtUtil.issuedAtMillis(legacy)).as("whole seconds from iat").isEqualTo(claims.getIssuedAt().getTime());
        assertThat(JwtUtil.issuedAtMillis(legacy) % 1000).isZero();
    }

    @Test
    void theKafkaFeedAppliesRecordsAndSkipsUnreadableOnes() throws Exception {
        TokenRevocationKafkaListener listener = new TokenRevocationKafkaListener(registry, objectMapper, "", "test");
        Instant now = Instant.now();
        byte[] value = objectMapper.writeValueAsBytes(UserTokensRevokedEvent.builder().userId("user-9").tokensValidAfter(now)
                .suspended(true).reason("SUSPENDED").stateAt(now).build());

        listener.handle(new ConsumerRecord<>(KafkaTopics.USER_TOKENS_REVOKED, 0, 0, "user-9", value));
        listener.handle(new ConsumerRecord<>(KafkaTopics.USER_TOKENS_REVOKED, 0, 1, "user-8", "not json".getBytes()));
        listener.handle(new ConsumerRecord<>(KafkaTopics.USER_TOKENS_REVOKED, 0, 2, "user-7", null));

        assertThat(registry.refusal("user-9", now.toEpochMilli() + 10)).isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);
        assertThat(registry.size()).isEqualTo(1);
        listener.start(); // no brokers configured: does nothing
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void theSnapshotIsRetriedUntilItLoadsAndAppliedWhenItDoes() {
        TokenRevocationSource down = () -> {
            throw new IllegalStateException("identity-service down");
        };
        TokenRevocationBootstrap failing = new TokenRevocationBootstrap(registry, List.of(down), 5, true);
        assertThat(failing.loadOnce()).isFalse();
        assertThat(failing.isLoaded()).isFalse();

        Instant now = Instant.now();
        TokenRevocationBootstrap working = new TokenRevocationBootstrap(registry, List.of(() -> List.of(UserTokensRevokedEvent.builder()
                .userId("user-3").tokensValidAfter(now).stateAt(now).build())), 5, true);
        assertThat(working.loadOnce()).isTrue();
        assertThat(registry.refusal("user-3", now.toEpochMilli() - 1)).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}

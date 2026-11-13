package org.coryd.common.security;

import io.jsonwebtoken.Claims;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Ops console access tokens are short-lived and carry the session's absolute end; nothing else changes. */
class JwtOpsSessionTest {

    private final JwtUtil jwt = build();

    private static JwtUtil build() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-secret-test-secret-test-secret-test-secret-0123456789");
        ReflectionTestUtils.setField(jwt, "expirationMs", 86_400_000L);
        jwt.buildParser();
        return jwt;
    }

    private static long lifetimeSeconds(Claims claims) {
        return (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
    }

    @Test
    void anOpsConsoleTokenLives15MinutesAndCarriesTheSessionEnd() {
        Instant sessionEnd = Instant.now().plus(12, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        String token = jwt.generateToken("a-1", null, "Ada", "Ops", UserRole.OPS_ADMIN, OpsRole.FINANCE, sessionEnd);
        Claims claims = jwt.extractAllClaims(token);
        assertThat(lifetimeSeconds(claims)).isEqualTo(15 * 60);
        CoRydUserDetails admin = jwt.userDetails(claims);
        assertThat(admin.getSessionExpiresAt()).isEqualTo(sessionEnd);
        assertThat(admin.getIssuedAtMs()).isEqualTo(((Number) claims.get(JwtUtil.ISSUED_AT_MS_CLAIM)).longValue());
        assertThat(jwt.expirationMsFor(UserRole.OPS_ADMIN, OpsRole.FINANCE)).isEqualTo(15 * 60_000L);
    }

    @Test
    void ridersDriversAndSystemTokensKeepTheirLifetimeAndNoSessionEnd() {
        Instant sessionEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        Claims rider = jwt.extractAllClaims(jwt.generateToken("r-1", "0801", "R", "R", UserRole.RIDER, OpsRole.SUPER_ADMIN, sessionEnd));
        assertThat(lifetimeSeconds(rider)).isEqualTo(86_400);
        assertThat(rider.get(JwtUtil.SESSION_EXPIRES_AT_CLAIM)).isNull();
        assertThat(lifetimeSeconds(jwt.extractAllClaims(jwt.generateToken("d-1", "0802", "D", "D", UserRole.DRIVER)))).isEqualTo(86_400);
        // a service's system token: OPS_ADMIN without an ops role
        Claims system = jwt.extractAllClaims(jwt.generateToken("system", null, "CoRyd", "System", UserRole.OPS_ADMIN));
        assertThat(lifetimeSeconds(system)).isEqualTo(86_400);
        assertThat(jwt.userDetails(system).getSessionExpiresAt()).isNull();
    }

    @Test
    void theOpsLifetimeIsConfigurable() {
        JwtUtil custom = build();
        custom.setOpsExpirationMinutes(5);
        Claims claims = custom.extractAllClaims(custom.generateToken("a-1", null, "Ada", "Ops", UserRole.OPS_ADMIN, OpsRole.SUPPORT));
        assertThat(lifetimeSeconds(claims)).isEqualTo(5 * 60);
    }
}

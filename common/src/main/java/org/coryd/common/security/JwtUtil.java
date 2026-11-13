package org.coryd.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.util.Names;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    /**
     * Ops console sessions (an OPS_ADMIN token carrying an opsRole) get short-lived access tokens - 15 minutes by default
     * (OPS_ACCESS_TOKEN_MINUTES in identity-service). Riders, drivers and service-to-service system tokens keep expirationMs.
     */
    @Value("${jwt.ops-expiration-minutes:15}")
    private long opsExpirationMinutes = 15;

    /**
     * Built once: the parser is immutable and thread-safe, and building one looks up jjwt's JSON reader through
     * ServiceLoader - done per request, a burst of first requests after a restart raced on that lookup and one
     * failed with NoSuchElementException (a 500 instead of a checked token).
     */
    private JwtParser parser;

    /** Absent only where JwtUtil is built by hand (unit tests). */
    private TokenRevocationRegistry revocations;

    @Autowired(required = false)
    public void setRevocations(TokenRevocationRegistry revocations) {
        this.revocations = revocations;
    }

    @PostConstruct
    void buildParser() {
        parser = Jwts.parser().verifyWith(signingKey()).build();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /** How long an access token for this session lives: the ops lifetime for an ops console session, else the normal one. */
    public long expirationMsFor(UserRole activeRole, OpsRole opsRole) {
        return activeRole == UserRole.OPS_ADMIN && opsRole != null ? opsExpirationMinutes * 60_000L : expirationMs;
    }

    /** Tests and hand-built instances. */
    public void setOpsExpirationMinutes(long minutes) {
        this.opsExpirationMinutes = minutes;
    }

    /**
     * The standard iat claim is whole seconds; a "sign out everywhere" and a sign-in a moment later can fall in the same
     * second. This carries the exact millisecond, so the revocation cut-off can tell them apart.
     */
    public static final String ISSUED_AT_MS_CLAIM = "iat_ms";

    /**
     * Ops console sessions only: when the whole session (the refresh-token lineage) ends at the latest, epoch millis - the
     * console reads it from GET /ops/me to warn before the absolute timeout.
     */
    public static final String SESSION_EXPIRES_AT_CLAIM = "sess_exp";

    public String generateToken(String userId, String phone, String firstName, String lastName, UserRole activeRole) {
        return generateToken(userId, phone, firstName, lastName, activeRole, null);
    }

    /**
     * opsRole is the ops admin's console role (OpsRole.CLAIM) - added only to an OPS_ADMIN session, and only when given;
     * services that don't care about it never read it.
     */
    public String generateToken(String userId, String phone, String firstName, String lastName, UserRole activeRole,
                                OpsRole opsRole) {
        return generateToken(userId, phone, firstName, lastName, activeRole, opsRole, null);
    }

    /** sessionExpiresAt: ops console sessions only (SESSION_EXPIRES_AT_CLAIM); ignored when null. */
    public String generateToken(String userId, String phone, String firstName, String lastName, UserRole activeRole,
                                OpsRole opsRole, java.time.Instant sessionExpiresAt) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(userId)
                .claim("phone", phone)
                .claim("firstName", firstName)
                .claim("lastName", lastName)
                .claim("role", activeRole.name());
        if (opsRole != null && activeRole == UserRole.OPS_ADMIN) {
            builder.claim(OpsRole.CLAIM, opsRole.name());
            if (sessionExpiresAt != null) {
                builder.claim(SESSION_EXPIRES_AT_CLAIM, sessionExpiresAt.toEpochMilli());
            }
        }
        return builder
                .claim(ISSUED_AT_MS_CLAIM, now)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMsFor(activeRole, opsRole)))
                .signWith(signingKey())
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    /** The token's claims when it's genuine and unexpired, else null - one parse for JwtFilter. */
    public Claims validClaims(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().before(new Date()) ? null : claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /** When the token was issued, to the millisecond if it says (tokens from before iat_ms: the start of its iat second). */
    public static long issuedAtMillis(Claims claims) {
        Object exact = claims.get(ISSUED_AT_MS_CLAIM);
        if (exact instanceof Number number) {
            return number.longValue();
        }
        Date issuedAt = claims.getIssuedAt();
        return issuedAt == null ? 0L : issuedAt.getTime();
    }

    /**
     * Genuine, unexpired, and its sessions not ended by ops (suspension, sign out everywhere) - what trip-service's
     * WebSocket CONNECT checks. JwtFilter uses validClaims and the registry itself, to say why it refused.
     */
    public boolean isTokenValid(String token) {
        Claims claims = validClaims(token);
        return claims != null && (revocations == null || revocations.refusal(claims.getSubject(), issuedAtMillis(claims)) == null);
    }

    /** Tokens minted before the name split carry a single fullName claim, so fall back to splitting it. */
    public CoRydUserDetails extractUserDetails(String token) {
        return userDetails(extractAllClaims(token));
    }

    public CoRydUserDetails userDetails(Claims claims) {
        String firstName = claims.get("firstName", String.class);
        String lastName = claims.get("lastName", String.class);
        if (firstName == null && lastName == null) {
            String legacy = claims.get("fullName", String.class);
            firstName = Names.firstName(legacy);
            lastName = Names.lastName(legacy);
        }
        Object sessionExpires = claims.get(SESSION_EXPIRES_AT_CLAIM);
        return new CoRydUserDetails(
                claims.getSubject(),
                claims.get("phone", String.class),
                firstName,
                lastName,
                UserRole.valueOf(claims.get("role", String.class)),
                claims.get(OpsRole.CLAIM, String.class),
                issuedAtMillis(claims),
                sessionExpires instanceof Number n ? java.time.Instant.ofEpochMilli(n.longValue()) : null
        );
    }
}

package org.coryd.ops.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * A browser's EventSource can't send an Authorization header, so the console first trades its admin token for a short-lived
 * stream token (POST /stream/token) and opens GET /stream?token=…. The token is an HMAC-SHA256-signed {sub, name, exp, jti}
 * under a key derived from the JWT secret with its own label - it opens a stream and nothing else (it isn't a JWT, and a JWT
 * isn't one of these). It only has to be valid when the stream opens; the stream itself ends at max-connection-minutes.
 */
@Component
public class StreamTokenService {

    public record Claims(String adminId, String adminName, Instant expiresAt, String tokenId) {
    }

    public record Issued(String token, Instant expiresAt) {
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    public StreamTokenService(@Value("${ops.stream.token-secret:${jwt.secret}}") String secret,
                              @Value("${ops.stream.token-ttl-seconds:60}") long ttlSeconds,
                              ObjectMapper objectMapper) {
        this.key = derive(secret);
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.objectMapper = objectMapper;
    }

    /** Tests move time. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public Issued issue(String adminId, String adminName) {
        Instant expiresAt = clock.instant().plus(ttl);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of("sub", adminId, "name", adminName == null ? adminId : adminName,
                    "exp", expiresAt.getEpochSecond(), "jti", UUID.randomUUID().toString()));
            String body = ENCODER.encodeToString(payload);
            return new Issued(body + "." + ENCODER.encodeToString(sign(body)), expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue a stream token", e);
        }
    }

    /** The admin the token was issued to - or 401 for a missing, forged, malformed or expired token. */
    public Claims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("A stream token is required - get one from POST /ops/stream/token");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new UnauthorizedException("Invalid stream token");
        }
        String body = token.substring(0, dot);
        byte[] signature;
        try {
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid stream token");
        }
        if (!MessageDigest.isEqual(sign(body), signature)) {
            throw new UnauthorizedException("Invalid stream token");
        }
        try {
            Map<?, ?> claims = objectMapper.readValue(DECODER.decode(body), Map.class);
            Instant expiresAt = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
            if (!clock.instant().isBefore(expiresAt)) {
                throw new UnauthorizedException("Stream token expired - get a new one from POST /ops/stream/token");
            }
            return new Claims((String) claims.get("sub"), (String) claims.get("name"), expiresAt, (String) claims.get("jti"));
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid stream token");
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(("coryd-ops-stream:" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

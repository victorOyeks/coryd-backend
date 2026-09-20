package org.coryd.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.util.Names;
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

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String generateToken(String userId, String phone, String firstName, String lastName, UserRole activeRole) {
        return Jwts.builder()
                .subject(userId)
                .claim("phone", phone)
                .claim("firstName", firstName)
                .claim("lastName", lastName)
                .claim("role", activeRole.name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(signingKey())
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /** Tokens minted before the name split carry a single fullName claim, so fall back to splitting it. */
    public CoRydUserDetails extractUserDetails(String token) {
        Claims claims = extractAllClaims(token);
        String firstName = claims.get("firstName", String.class);
        String lastName = claims.get("lastName", String.class);
        if (firstName == null && lastName == null) {
            String legacy = claims.get("fullName", String.class);
            firstName = Names.firstName(legacy);
            lastName = Names.lastName(legacy);
        }
        return new CoRydUserDetails(
                claims.getSubject(),
                claims.get("phone", String.class),
                firstName,
                lastName,
                UserRole.valueOf(claims.get("role", String.class))
        );
    }
}

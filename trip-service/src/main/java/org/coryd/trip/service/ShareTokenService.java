package org.coryd.trip.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless, HMAC-signed "share this trip" links - no database table to track/revoke them.
 * A token is just base64url(bookingId|expiryEpochSecond) + "." + its signature, so verifying
 * one is pure computation, not a lookup - the whole point for a link an emergency contact
 * (who has no account) opens without logging in.
 */
@Slf4j
@Service
public class ShareTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec signingKey;
    private final String baseUrl;

    public ShareTokenService(@Value("${share.link.secret}") String secret,
                              @Value("${share.link.base-url}") String baseUrl) {
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.baseUrl = baseUrl;
    }

    public String generateLink(String bookingId, Instant expiresAt) {
        return baseUrl + "/shared/" + generateToken(bookingId, expiresAt);
    }

    public String generateToken(String bookingId, Instant expiresAt) {
        String payload = bookingId + "|" + expiresAt.getEpochSecond();
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public Optional<String> verifyAndExtractBookingId(String token) {
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String encodedPayload = parts[0];
        if (!sign(encodedPayload).equals(parts[1])) {
            log.warn("Share token signature mismatch");
            return Optional.empty();
        }

        String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        String[] payloadParts = payload.split("\\|", 2);
        if (payloadParts.length != 2) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() > Long.parseLong(payloadParts[1])) {
            return Optional.empty();
        }
        return Optional.of(payloadParts[0]);
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return encode(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign share token", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

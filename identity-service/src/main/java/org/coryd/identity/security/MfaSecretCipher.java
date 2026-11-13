package org.coryd.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Two-factor secrets at rest: AES-256-GCM, a fresh 96-bit IV each time, stored as "v1:" + base64(iv || ciphertext+tag).
 * The key is MFA_ENCRYPTION_KEY (32 bytes base64, or any passphrase - hashed to 32 bytes); without it, a key derived from
 * the JWT secret under its own label, so the two are never the same key. Changing the key makes existing secrets
 * unreadable (those admins need a super-admin reset), so set MFA_ENCRYPTION_KEY once, before anyone enrolls.
 */
@Component
public class MfaSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public MfaSecretCipher(@Value("${ops.mfa.encryption-key:}") String configuredKey, @Value("${jwt.secret}") String jwtSecret) {
        this.key = new SecretKeySpec(keyBytes(configuredKey, jwtSecret), "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't protect the two-factor secret", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unreadable two-factor secret");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key (MFA_ENCRYPTION_KEY changed) or tampered value - never echo the value.
            throw new IllegalStateException("Unreadable two-factor secret");
        }
    }

    static byte[] keyBytes(String configured, String jwtSecret) {
        try {
            if (configured != null && !configured.isBlank()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(configured.trim());
                    if (decoded.length == 32) {
                        return decoded;
                    }
                } catch (IllegalArgumentException notBase64) {
                    // a passphrase
                }
                return MessageDigest.getInstance("SHA-256").digest(configured.trim().getBytes(StandardCharsets.UTF_8));
            }
            return MessageDigest.getInstance("SHA-256").digest(("coryd-ops-mfa-secret:" + jwtSecret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

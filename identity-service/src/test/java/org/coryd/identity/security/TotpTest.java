package org.coryd.identity.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 appendix B, SHA1 seed "12345678901234567890" - the 8-digit values' last 6 digits. */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(RFC_SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111111)))).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void oneStepEitherSideCountsTwoStepsDont() {
        String secret = Totp.newSecret();
        Instant now = Instant.parse("2026-11-13T10:00:15Z");
        long step = Totp.step(now);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step), now, null)).isEqualTo(step);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step - 2), now, null)).isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step + 2), now, null)).isEqualTo(-1);
    }

    @Test
    void aCodeIsGoodOnceAndOnlyAfterTheLastOneUsed() {
        String secret = Totp.newSecret();
        Instant now = Instant.parse("2026-11-13T10:00:15Z");
        long step = Totp.step(now);
        String code = Totp.code(secret, step);
        assertThat(Totp.matchingStep(secret, code, now, step)).as("replay of the same step").isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step - 1), now, step)).as("an older step").isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, step + 1), now.plusSeconds(30), step)).isEqualTo(step + 1);
    }

    @Test
    void junkIsRefused() {
        String secret = Totp.newSecret();
        Instant now = Instant.now();
        assertThat(Totp.matchingStep(secret, null, now, null)).isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, "12345", now, null)).isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, "abcdef", now, null)).isEqualTo(-1);
        assertThat(Totp.unbase32(Totp.base32(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}))).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }
}

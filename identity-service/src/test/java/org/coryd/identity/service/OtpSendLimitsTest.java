package org.coryd.identity.service;

import org.coryd.common.exception.BadRequestException;
import org.coryd.identity.repository.OtpCodeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtpSendLimitsTest {

    private static final String PHONE = "07010002001";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final OtpCodeRepository otps = mock(OtpCodeRepository.class);
    private final OtpSendLimits limits =
            new OtpSendLimits(otps, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60), 5);

    @Test
    void firstCodeForAPhoneIsSent() {
        when(otps.findLatestIssuedAt(PHONE)).thenReturn(Optional.empty());
        when(otps.countIssuedSince(eq(PHONE), any())).thenReturn(0L);

        assertThatCode(() -> limits.checkCanSend(PHONE)).doesNotThrowAnyException();
    }

    @Test
    void aSecondCodeInsideTheCooldownIsRefusedWithTheWait() {
        when(otps.findLatestIssuedAt(PHONE)).thenReturn(Optional.of(NOW.minusSeconds(20)));

        assertThatThrownBy(() -> limits.checkCanSend(PHONE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Please wait 40 seconds before requesting another code");
    }

    @Test
    void afterTheCooldownACodeIsSentAgain() {
        when(otps.findLatestIssuedAt(PHONE)).thenReturn(Optional.of(NOW.minusSeconds(61)));
        when(otps.countIssuedSince(eq(PHONE), any())).thenReturn(4L);

        assertThatCode(() -> limits.checkCanSend(PHONE)).doesNotThrowAnyException();
    }

    @Test
    void theSixthCodeInAnHourIsRefusedUntilTheOldestDropsOut() {
        when(otps.findLatestIssuedAt(PHONE)).thenReturn(Optional.of(NOW.minusSeconds(120)));
        when(otps.countIssuedSince(PHONE, NOW.minus(Duration.ofHours(1)))).thenReturn(5L);
        when(otps.findEarliestIssuedSince(PHONE, NOW.minus(Duration.ofHours(1))))
                .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(45))));

        assertThatThrownBy(() -> limits.checkCanSend(PHONE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Too many codes requested for this number - try again in 15 minutes");
    }
}

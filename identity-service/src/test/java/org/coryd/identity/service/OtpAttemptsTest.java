package org.coryd.identity.service;

import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.exception.BadRequestException;
import org.coryd.identity.entity.OtpCode;
import org.coryd.identity.repository.OtpCodeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A code stops working after OtpAttempts.MAX wrong guesses. */
class OtpAttemptsTest {

    private final OtpCodeRepository repository = mock(OtpCodeRepository.class);
    private final OtpAttempts attempts = new OtpAttempts(repository);

    @Test
    void wrongCodesAreCountedAndTheFifthSpendsTheCode() {
        OtpCode otp = OtpCode.builder().id("otp-1").phone("07010001002").code("123456")
                .purpose(OtpPurpose.PASSWORD_RESET).expiresAt(Instant.now().plusSeconds(300)).build();
        when(repository.findById("otp-1")).thenReturn(Optional.of(otp));

        for (int i = 1; i < OtpAttempts.MAX; i++) {
            assertThatThrownBy(() -> attempts.recordWrongCode("otp-1"))
                    .isInstanceOf(BadRequestException.class).hasMessage("Incorrect OTP");
            assertThat(otp.isUsed()).isFalse();
        }
        assertThatThrownBy(() -> attempts.recordWrongCode("otp-1"))
                .isInstanceOf(BadRequestException.class).hasMessage(OtpAttempts.TOO_MANY);
        assertThat(otp.getAttempts()).isEqualTo(OtpAttempts.MAX);
        assertThat(otp.isUsed()).isTrue();
    }
}

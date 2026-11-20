package org.coryd.identity.service;

import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.security.JwtUtil;
import org.coryd.identity.client.ProfileServiceClient;
import org.coryd.identity.security.OpsSecurityPolicy;
import org.coryd.identity.dto.ResetPasswordRequest;
import org.coryd.identity.dto.SetPasswordRequest;
import org.coryd.identity.dto.VerifyOtpRequest;
import org.coryd.identity.entity.OtpCode;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.OtpCodeRepository;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserDeviceRepository;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** set-password is onboarding only, a password reset ends every session, and a wrong OTP is counted. */
class AuthServicePasswordAndOtpTest {

    private final UserRepository users = mock(UserRepository.class);
    private final OtpCodeRepository otps = mock(OtpCodeRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Environment environment = mock(Environment.class);
    private final SessionRevocationService sessions = mock(SessionRevocationService.class);
    private final OtpAttempts otpAttempts = mock(OtpAttempts.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);

    private final AuthService service = new AuthService(users, mock(BetaSignupService.class), otps, refreshTokens,
            mock(UserDeviceRepository.class), mock(TokenFamilyRevoker.class), mock(JwtUtil.class), encoder, environment, kafka,
            mock(ProfileServiceClient.class), mock(OpsSecurityPolicy.class), mock(MfaChallengeService.class), sessions, otpAttempts, mock(OtpSendLimits.class));

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").phone("07010001002").build();
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(users.findByPhone("07010001002")).thenReturn(Optional.of(user));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(encoder.encode(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
    }

    @Test
    void setPasswordWorksDuringOnboarding() {
        service.setPassword("user-1", new SetPasswordRequest("Test1234!", "Test1234!"));

        assertThat(user.getPasswordHash()).isEqualTo("hash:Test1234!");
    }

    @Test
    void setPasswordCannotReplaceAnExistingPassword() {
        user.setPasswordHash("hash:Original1!");

        assertThatThrownBy(() -> service.setPassword("user-1", new SetPasswordRequest("Hijack123!", "Hijack123!")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("change password");
        assertThat(user.getPasswordHash()).isEqualTo("hash:Original1!");
    }

    @Test
    void resetPasswordEndsEverySessionIncludingAccessTokens() {
        OtpCode otp = otp("123456", OtpPurpose.PASSWORD_RESET);
        when(otps.findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc("07010001002", OtpPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(otp));

        service.resetPassword(new ResetPasswordRequest("07010001002", "123456", "NewPass12!", "NewPass12!"));

        assertThat(user.getPasswordHash()).isEqualTo("hash:NewPass12!");
        verify(sessions).endAllSessions(user, UserTokensRevokedEvent.SIGNED_OUT_EVERYWHERE);
    }

    @Test
    void aWrongResetCodeIsCounted() {
        OtpCode otp = otp("123456", OtpPurpose.PASSWORD_RESET);
        when(otps.findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc("07010001002", OtpPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(otp));
        doThrow(new BadRequestException("Incorrect OTP")).when(otpAttempts).recordWrongCode("otp-1");

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("07010001002", "999999", "NewPass12!", "NewPass12!")))
                .isInstanceOf(BadRequestException.class);
        verify(otpAttempts).recordWrongCode("otp-1");
        verify(encoder, never()).encode(anyString());
        verify(sessions, never()).endAllSessions(any(), anyString());
    }

    @Test
    void aWrongSignupCodeIsCounted() {
        OtpCode otp = otp("123456", OtpPurpose.SIGNUP);
        when(otps.findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc("07010001002", OtpPurpose.SIGNUP))
                .thenReturn(Optional.of(otp));
        doThrow(new BadRequestException("Incorrect OTP")).when(otpAttempts).recordWrongCode("otp-1");

        assertThatThrownBy(() -> service.verifyOtp(new VerifyOtpRequest("07010001002", "999999", null)))
                .isInstanceOf(BadRequestException.class);
        verify(otpAttempts).recordWrongCode(eq("otp-1"));
        assertThat(otp.isUsed()).isFalse();
    }

    private static OtpCode otp(String code, OtpPurpose purpose) {
        return OtpCode.builder().id("otp-1").phone("07010001002").code(code).purpose(purpose)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES)).build();
    }
}

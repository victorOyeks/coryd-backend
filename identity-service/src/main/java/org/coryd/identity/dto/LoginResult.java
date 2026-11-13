package org.coryd.identity.dto;

/**
 * What a sign-in answers: a session ({@link AuthResponse}), or - for an ops admin with two-factor sign-in - the next step
 * ({@link org.coryd.identity.dto.mfa.MfaChallengeResponse}, {@link org.coryd.identity.dto.mfa.MfaEnrollmentResponse}).
 */
public interface LoginResult {
}

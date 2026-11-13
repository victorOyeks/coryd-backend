package org.coryd.identity.dto.mfa;

import org.coryd.identity.dto.LoginResult;

import java.time.Instant;

/** Password right, two-factor required but not set up: set it up with this token (POST /auth/mfa/enroll/setup, /confirm). */
public record MfaEnrollmentResponse(boolean mfaEnrollmentRequired, String enrollToken, Instant enrollTokenExpiresAt)
        implements LoginResult {
}

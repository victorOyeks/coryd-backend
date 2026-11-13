package org.coryd.identity.dto.mfa;

import org.coryd.identity.dto.AuthResponse;

import java.util.List;

/** Enrollment at sign-in finished: the console session, and the recovery codes (shown once). */
public record MfaEnrolledResponse(AuthResponse session, List<String> recoveryCodes) {
}

package org.coryd.identity.dto.mfa;

import org.coryd.identity.dto.LoginResult;

import java.time.Instant;

/** Password right, two-factor on: send a code with this token to POST /auth/mfa/verify. Single use, 5 minutes. */
public record MfaChallengeResponse(boolean mfaRequired, String mfaToken, Instant mfaTokenExpiresAt) implements LoginResult {
}

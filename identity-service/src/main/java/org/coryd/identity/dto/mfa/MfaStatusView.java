package org.coryd.identity.dto.mfa;

import java.time.Instant;

/**
 * An ops admin's two-factor state and the console's sign-in rules. mfaRequired: OPS_MFA_REQUIRED. setupPending: setup was
 * started and not confirmed.
 */
public record MfaStatusView(boolean mfaEnabled, boolean mfaRequired, Instant enabledAt, long recoveryCodesLeft,
                            boolean setupPending, long accessTokenMinutes, long sessionIdleMinutes, long sessionAbsoluteHours) {
}

package org.coryd.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The ops console's sign-in rules (phase 7), from configuration:
 * <ul>
 *   <li>{@code ops.mfa-required} (OPS_MFA_REQUIRED, default false): every ops admin must use two-factor sign-in; one who
 *   hasn't set it up is taken through enrollment at sign-in. Off by default so nobody is locked out on deploy.</li>
 *   <li>{@code ops.session.idle-minutes} (OPS_SESSION_IDLE_MINUTES, 30): a console refresh token dies after this long
 *   unused; each refresh slides it.</li>
 *   <li>{@code ops.session.absolute-hours} (OPS_SESSION_ABSOLUTE_HOURS, 12): however active, the session ends this long
 *   after sign-in.</li>
 * </ul>
 * The access-token lifetime is JwtUtil's {@code jwt.ops-expiration-minutes} (OPS_ACCESS_TOKEN_MINUTES, 15). Also the clock
 * the session and two-factor rules read, so tests can move time.
 */
@Component
public class OpsSecurityPolicy {

    public static final String SESSION_TIMED_OUT = "Your session timed out - sign in again";

    private volatile boolean mfaRequired;
    private final long idleMinutes;
    private final long absoluteHours;
    private final long accessTokenMinutes;
    private volatile Clock clock = Clock.systemUTC();

    public OpsSecurityPolicy(@Value("${ops.mfa-required:false}") boolean mfaRequired,
                             @Value("${ops.session.idle-minutes:30}") long idleMinutes,
                             @Value("${ops.session.absolute-hours:12}") long absoluteHours,
                             @Value("${jwt.ops-expiration-minutes:15}") long accessTokenMinutes) {
        this.mfaRequired = mfaRequired;
        this.idleMinutes = idleMinutes;
        this.absoluteHours = absoluteHours;
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public boolean mfaRequired() {
        return mfaRequired;
    }

    public long idleMinutes() {
        return idleMinutes;
    }

    public long absoluteHours() {
        return absoluteHours;
    }

    public long accessTokenMinutes() {
        return accessTokenMinutes;
    }

    public Duration idle() {
        return Duration.ofMinutes(idleMinutes);
    }

    public Duration absolute() {
        return Duration.ofHours(absoluteHours);
    }

    public Instant now() {
        return clock.instant();
    }

    /** Tests turn the requirement on and off without a new application context. */
    public void overrideMfaRequired(boolean required) {
        this.mfaRequired = required;
    }

    /** Tests move time. */
    public void useClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }
}

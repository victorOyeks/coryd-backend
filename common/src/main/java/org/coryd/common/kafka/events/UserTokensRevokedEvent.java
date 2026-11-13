package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A user's session state, as identity-service now has it - published on the compacted topic
 * {@code coryd-user-tokens-revoked}, keyed by userId, so the latest one per user is kept for good and every service
 * replays them all at startup. Every service's JwtFilter refuses an access token issued before {@code tokensValidAfter},
 * and every token of a {@code suspended} account. See docs/ops-console/accounts.md.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTokensRevokedEvent {

    public static final String SUSPENDED = "SUSPENDED";
    public static final String REINSTATED = "REINSTATED";
    public static final String SIGNED_OUT_EVERYWHERE = "SIGNED_OUT_EVERYWHERE";
    public static final String DELETED = "DELETED";

    private String userId;
    /** Access tokens issued before this are refused. Null when nothing was ever revoked (a reinstate of a legacy suspension). */
    private Instant tokensValidAfter;
    /** True while the account is suspended - every token is refused, with the suspension message. */
    private boolean suspended;
    /** SUSPENDED, REINSTATED, SIGNED_OUT_EVERYWHERE or DELETED - what just happened. */
    private String reason;
    /** When this state was set - the newer of two states for the same user wins, whichever way it arrived. */
    private Instant stateAt;
}

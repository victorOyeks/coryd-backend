package org.coryd.common.security.revocation;

import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules every service's JwtFilter applies, and how the Kafka feed and the snapshot merge whatever order they arrive in. */
class TokenRevocationRegistryTest {

    private final TokenRevocationRegistry registry = new TokenRevocationRegistry();
    private final Instant cut = Instant.parse("2026-11-13T10:00:00.500Z");

    @Test
    void someoneNeverRevokedIsLetThrough() {
        assertThat(registry.refusal("user-1", 0)).isNull();
        assertThat(registry.refusal(null, 0)).isNull();
    }

    @Test
    void tokensFromBeforeTheCutOffAreRefusedAndLaterOnesAccepted() {
        registry.apply("user-1", cut, false, cut);

        assertThat(registry.refusal("user-1", cut.toEpochMilli() - 1)).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
        assertThat(registry.refusal("user-1", cut.toEpochMilli())).isNull();
        assertThat(registry.refusal("user-1", cut.toEpochMilli() + 1)).isNull();
        assertThat(registry.refusal("user-2", 0)).as("other users untouched").isNull();
    }

    @Test
    void aSuspendedAccountsTokensAreAllRefusedWithTheSuspensionMessage() {
        registry.apply("user-1", cut, true, cut);

        assertThat(registry.refusal("user-1", cut.toEpochMilli() + 60_000)).isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);
    }

    @Test
    void reinstatingLiftsTheSuspensionButNeverMovesTheCutOffBack() {
        registry.apply("user-1", cut, true, cut);
        registry.apply("user-1", null, false, cut.plusSeconds(60));

        assertThat(registry.refusal("user-1", cut.toEpochMilli() - 1)).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
        assertThat(registry.refusal("user-1", cut.plusSeconds(61).toEpochMilli())).isNull();
    }

    @Test
    void anOlderStateArrivingLateDoesntUndoANewerOne() {
        // the snapshot already says reinstated; the Kafka replay of the suspension comes afterwards
        registry.apply("user-1", cut, false, cut.plusSeconds(60));
        registry.apply(UserTokensRevokedEvent.builder().userId("user-1").tokensValidAfter(cut).suspended(true)
                .reason(UserTokensRevokedEvent.SUSPENDED).stateAt(cut).build());

        assertThat(registry.refusal("user-1", cut.plusSeconds(120).toEpochMilli())).isNull();

        // and the other way round: a newer suspension beats an older snapshot
        registry.apply("user-2", cut, true, cut.plusSeconds(60));
        registry.apply("user-2", null, false, cut);
        assertThat(registry.refusal("user-2", cut.plusSeconds(120).toEpochMilli())).isEqualTo(TokenRevocationRegistry.SUSPENDED_MESSAGE);
    }

    @Test
    void aLaterCutOffWins() {
        registry.apply("user-1", cut, false, cut);
        registry.apply("user-1", cut.plusSeconds(30), false, cut.plusSeconds(30));
        registry.apply("user-1", cut, false, cut.plusSeconds(40));

        assertThat(registry.refusal("user-1", cut.plusSeconds(20).toEpochMilli())).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
    }
}

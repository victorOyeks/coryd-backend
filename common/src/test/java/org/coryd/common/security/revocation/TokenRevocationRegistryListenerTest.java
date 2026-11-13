package org.coryd.common.security.revocation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRevocationRegistryListenerTest {

    @Test
    void listenersHearEveryChangeAndAFailingOneDoesntStopTheOthers() {
        TokenRevocationRegistry registry = new TokenRevocationRegistry();
        List<String> heard = new ArrayList<>();
        registry.addListener(id -> {
            throw new IllegalStateException("boom");
        });
        registry.addListener(heard::add);
        registry.apply("u-1", Instant.now(), false, Instant.now());
        registry.apply("u-2", null, true, Instant.now());
        assertThat(heard).containsExactly("u-1", "u-2");
        assertThat(registry.refusal("u-1", 0)).isEqualTo(TokenRevocationRegistry.SIGNED_OUT_MESSAGE);
    }
}

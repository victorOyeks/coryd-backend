package org.coryd.notification.broadcast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The broadcast rate limit: sends are spaced 1/rate apart, and time already passed isn't slept again. */
class SendPacerTest {

    @Test
    void spacesSendsToTheConfiguredRate() throws Exception {
        long[] now = {0};
        List<Long> sleeps = new ArrayList<>();
        SendPacer pacer = new SendPacer(4, () -> now[0], millis -> {
            sleeps.add(millis);
            now[0] += millis * 1_000_000;
        });
        for (int i = 0; i < 5; i++) {
            pacer.acquire();
        }
        assertThat(sleeps).containsExactly(250L, 250L, 250L, 250L);
        assertThat(now[0]).as("5 sends at 4/s take a second").isEqualTo(1_000_000_000L);
    }

    @Test
    void slowSendsDontWaitAnyLonger() throws Exception {
        long[] now = {0};
        List<Long> sleeps = new ArrayList<>();
        SendPacer pacer = new SendPacer(10, () -> now[0], sleeps::add);
        pacer.acquire();
        now[0] += 500_000_000L;
        pacer.acquire();
        assertThat(sleeps).isEmpty();
    }
}

package org.coryd.notification.controller;

import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.exception.BadRequestException;
import org.coryd.notification.controller.internal.InternalOpsDeliveryController;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which channel each SOS alert went out on, for the Ops Console - a bounded window only. */
class InternalOpsDeliveryControllerTest {

    private final NotificationLogRepository repository = mock(NotificationLogRepository.class);
    private final InternalOpsDeliveryController controller = new InternalOpsDeliveryController(repository);

    @Test
    void deliveriesInTheWindowComeBackWithChannelAndOutcome() {
        Instant from = Instant.parse("2026-11-13T10:00:00Z");
        Instant to = from.plusSeconds(900);
        NotificationLog sms = NotificationLog.builder().recipientPhone("08011110000").channel(NotificationChannel.SMS).eventType("SOS_TRIGGERED")
                .delivered(true).build();
        sms.setCreatedAt(from.plusSeconds(2));
        when(repository.findByEventTypeInAndCreatedAtBetweenOrderByCreatedAtAsc(eq(List.of("SOS_TRIGGERED")), eq(from), eq(to)))
                .thenReturn(List.of(sms));

        var rows = controller.deliveries(List.of("SOS_TRIGGERED"), from, to).data();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.channel()).isEqualTo("SMS");
            assertThat(r.recipientPhone()).isEqualTo("08011110000");
            assertThat(r.delivered()).isTrue();
        });
    }

    @Test
    void anOpenEndedOrBackwardsWindowIsRefused() {
        Instant from = Instant.parse("2026-11-13T10:00:00Z");
        assertThatThrownBy(() -> controller.deliveries(List.of("SOS_TRIGGERED"), from, from.plusSeconds(7 * 3600)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.deliveries(List.of("SOS_TRIGGERED"), from, from.minusSeconds(1)))
                .isInstanceOf(BadRequestException.class);
    }
}

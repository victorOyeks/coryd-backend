package org.coryd.notification.broadcast;

import org.coryd.common.exception.BadRequestException;
import org.coryd.notification.config.WhatsAppProperties;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.gateway.whatsapp.WhatsAppService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broadcasts against notification-service's own migrations, with profile-service's audience and the providers stubbed:
 * validation, the preview's counts (opt-outs, devices), quiet hours, creating and auditing, sending with per-recipient
 * channel outcomes and opt-outs left out, the rate limit, cancelling before and during a send, and the hourly cap.
 */
@DataJpaTest(properties = "broadcast.check-ms=3600000")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({BroadcastService.class, BroadcastSender.class, BroadcastLookups.class, BroadcastProperties.class, BroadcastAuditWriter.class,
        WhatsAppProperties.class, JacksonAutoConfiguration.class})
class BroadcastIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 10:00 Lagos - outside quiet hours. */
    static final Instant DAY = Instant.parse("2026-11-13T09:00:00Z");
    /** 22:30 Lagos. */
    static final Instant NIGHT = Instant.parse("2026-11-13T21:30:00Z");
    static final OpsAdmin ADA = new OpsAdmin("admin-1", "Ada Ops");

    @Autowired BroadcastService service;
    @Autowired BroadcastSender sender;
    @Autowired BroadcastProperties properties;
    @Autowired BroadcastRepository broadcasts;
    @Autowired BroadcastRecipientRepository recipients;
    @Autowired NotificationOpsAuditRepository audit;
    @Autowired NotificationPreferencesRepository preferences;
    @Autowired DeviceTokenRepository devices;
    @Autowired UserNotificationRepository feed;
    @Autowired NotificationLogRepository logs;
    @Autowired JdbcTemplate jdbc;

    @MockBean AudienceClient audienceClient;
    @MockBean FcmService fcm;
    @MockBean TermiiSmsService sms;
    @MockBean EmailService email;
    @MockBean WhatsAppService whatsApp;

    List<AudienceClient.Member> people;
    List<Long> paced;

    @BeforeEach
    void setUp() {
        recipients.deleteAll();
        broadcasts.deleteAll();
        audit.deleteAll();
        preferences.deleteAll();
        devices.deleteAll();
        feed.deleteAll();
        logs.deleteAll();
        properties.setMaxPerHour(10);
        properties.setRatePerSecond(10);
        service.useClock(Clock.fixed(DAY, ZoneOffset.UTC));
        sender.useClock(Clock.fixed(DAY, ZoneOffset.UTC));
        paced = new ArrayList<>();
        long[] ticker = {0};
        sender.usePacer(rate -> new SendPacer(rate, () -> ticker[0], ms -> {
            paced.add(ms);
            ticker[0] += ms * 1_000_000;
        }));

        people = List.of(
                new AudienceClient.Member("u1", "RIDER", "R-000001", "Amaka", "08011110001", "amaka@example.com"),
                new AudienceClient.Member("u2", "RIDER", "R-000002", "Bola", "08011110002", null),
                new AudienceClient.Member("u3", "RIDER", "R-000003", "Chidi", null, null),
                new AudienceClient.Member("u4", "RIDER", "R-000004", "Dayo", "08011110004", "dayo@example.com"));
        when(audienceClient.count(any())).thenReturn(new AudienceClient.Count(4, 3, 2, List.of()));
        when(audienceClient.page(any(), isNull(), any(Integer.class))).thenReturn(new AudienceClient.Page(people.subList(0, 2), "u2"));
        when(audienceClient.page(any(), eq("u2"), any(Integer.class))).thenReturn(new AudienceClient.Page(people.subList(2, 4), null));
        preferences.save(NotificationPreferences.builder().userId("u4").promotionalOptIn(false).build());
        devices.save(DeviceToken.builder().userId("u1").fcmToken("tok-1").build());
        devices.save(DeviceToken.builder().userId("u2").fcmToken("tok-2").build());
        when(fcm.sendPush(eq("u1"), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new FcmService.PushResult(true, "m-1", null));
        when(fcm.sendPush(eq("u2"), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new FcmService.PushResult(false, null, "UNREGISTERED"));
        when(sms.send(anyString(), anyString())).thenReturn(true);
        when(email.send(anyString(), anyString(), anyString())).thenReturn(true);
        when(whatsApp.send(anyString(), any())).thenReturn(Optional.empty());
    }

    BroadcastDtos.ComposeRequest request(List<BroadcastChannel> channels, boolean urgent, Instant at, String reason) {
        return new BroadcastDtos.ComposeRequest("Road closure", "Third Mainland Bridge is closed Sunday - leave early.", channels,
                new BroadcastDtos.AudienceSpec(AudienceType.ALL_RIDERS, null, null, null), urgent, at, reason);
    }

    @Test
    void badBroadcastsAreRefusedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> service.create(request(List.of(BroadcastChannel.PUSH), false, null, " "), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.create(request(List.of(), false, null, "x"), ADA)).hasMessageContaining("channel");
        assertThatThrownBy(() -> service.create(request(List.of(BroadcastChannel.WHATSAPP), false, null, "x"), ADA))
                .hasMessageContaining("WhatsApp isn't available");
        assertThatThrownBy(() -> service.create(request(List.of(BroadcastChannel.SMS), false, DAY.minusSeconds(3600), "x"), ADA))
                .hasMessageContaining("past");
        assertThatThrownBy(() -> service.create(new BroadcastDtos.ComposeRequest("t", "b", List.of(BroadcastChannel.SMS),
                new BroadcastDtos.AudienceSpec(AudienceType.ACTIVE_RIDERS, null, null, null), false, null, "x"), ADA))
                .hasMessageContaining("N between 1 and 365");
        when(audienceClient.count(any())).thenReturn(new AudienceClient.Count(0, 0, 0, List.of("R-ABCDEF")));
        assertThatThrownBy(() -> service.create(request(List.of(BroadcastChannel.SMS), false, null, "x"), ADA))
                .hasMessageContaining("Nobody").hasMessageContaining("R-ABCDEF");
        assertThat(broadcasts.count()).isZero();
    }

    @Test
    void thePreviewCountsRecipientsOptOutsAndReachPerChannel() {
        BroadcastDtos.Preview preview = service.preview(request(List.of(BroadcastChannel.PUSH, BroadcastChannel.SMS), false, null, null));
        assertThat(preview.recipients()).isEqualTo(4);
        assertThat(preview.optedOut()).isEqualTo(1);
        assertThat(preview.willReceive()).isEqualTo(3);
        assertThat(preview.reachable()).containsEntry("PUSH", 2L).containsEntry("SMS", 3L).doesNotContainKey("EMAIL");
        assertThat(preview.sample().sms()).startsWith("CoRyd: Road closure - ");
        assertThat(preview.smsSegments()).isEqualTo(1);
        assertThat(preview.deferredForQuietHours()).isFalse();
        assertThat(service.preview(request(List.of(BroadcastChannel.SMS), true, null, null)).willReceive()).as("urgent ignores opt-outs").isEqualTo(4);
    }

    @Test
    void aNonUrgentBroadcastNeverGoesOutInQuietHours() {
        service.useClock(Clock.fixed(NIGHT, ZoneOffset.UTC));
        BroadcastDtos.View view = service.create(request(List.of(BroadcastChannel.SMS), false, null, "Weekend notice"), ADA);
        assertThat(view.deferredForQuietHours()).isTrue();
        assertThat(view.scheduledFor()).isEqualTo(Instant.parse("2026-11-14T06:00:00Z"));
        BroadcastDtos.View urgent = service.create(request(List.of(BroadcastChannel.SMS), true, null, "Safety notice"), ADA);
        assertThat(urgent.deferredForQuietHours()).isFalse();
        assertThat(urgent.scheduledFor()).isEqualTo(NIGHT);

        sender.useClock(Clock.fixed(NIGHT, ZoneOffset.UTC));
        assertThat(sender.sendNow(view.id())).as("not due").isFalse();
        assertThat(sender.sendNow(urgent.id())).isTrue();
        assertThat(broadcasts.findById(urgent.id()).orElseThrow().getStatus()).isEqualTo(Broadcast.Status.SENT);
    }

    @Test
    void sendingRecordsEveryRecipientsOutcomePerChannelAndLeavesOptOutsOut() {
        BroadcastDtos.View created = service.create(request(List.of(BroadcastChannel.PUSH, BroadcastChannel.SMS, BroadcastChannel.EMAIL),
                false, null, "Bridge closure"), ADA);
        assertThat(created.status()).isEqualTo("SCHEDULED");
        assertThat(audit.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("BROADCAST", created.id()))
                .singleElement().satisfies(a -> {
                    assertThat(a.getAction()).isEqualTo("BROADCAST_CREATED");
                    assertThat(a.getActorName()).isEqualTo("Ada Ops");
                    assertThat(a.getReason()).isEqualTo("Bridge closure");
                });

        assertThat(sender.sendNow(created.id())).isTrue();
        assertThat(sender.sendNow(created.id())).as("sent once").isFalse();

        BroadcastDtos.View done = service.detail(created.id());
        assertThat(done.status()).isEqualTo("SENT");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.stats().recipients()).isEqualTo(4);
        assertThat(done.stats().skipped()).as("u3 has no way in on these channels, u4 opted out").isEqualTo(2);
        assertThat(done.stats().pending()).isZero();
        assertThat(done.stats().byChannel().get("PUSH")).containsEntry("SENT", 1L).containsEntry("FAILED", 1L).containsEntry("NO_DEVICE", 1L);
        assertThat(done.stats().byChannel().get("SMS")).containsEntry("SENT", 2L).containsEntry("NO_PHONE", 1L);
        assertThat(done.stats().byChannel().get("EMAIL")).containsEntry("SENT", 1L).containsEntry("NO_EMAIL", 2L);

        var rows = service.recipients(created.id(), null, 0, 10).content();
        assertThat(rows).extracting(BroadcastDtos.RecipientView::userId, BroadcastDtos.RecipientView::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("u1", "SENT"), org.assertj.core.groups.Tuple.tuple("u2", "PARTIAL"),
                        org.assertj.core.groups.Tuple.tuple("u3", "SKIPPED"), org.assertj.core.groups.Tuple.tuple("u4", "SKIPPED"));
        assertThat(rows.get(3).skipReason()).contains("announcements off");
        assertThat(rows.get(2).skipReason()).contains("No way to reach");
        verify(sms, never()).send(eq("08011110004"), anyString());
        verify(email, never()).send(eq("dayo@example.com"), anyString(), anyString());
        assertThat(feed.count()).as("a feed entry for each push recipient").isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from notification.notification_log where event_type = 'BROADCAST'", Long.class))
                .isEqualTo(5);
        assertThat(paced).as("one pacer slot per recipient sent, 10/s").hasSize(2).allMatch(ms -> ms == 100L);
    }

    @Test
    void anUrgentBroadcastReachesThoseWhoTurnedAnnouncementsOff() {
        BroadcastDtos.View created = service.create(request(List.of(BroadcastChannel.SMS), true, null, "Safety"), ADA);
        sender.sendNow(created.id());
        verify(sms, times(1)).send(eq("08011110004"), anyString());
        assertThat(service.detail(created.id()).stats().skipped()).as("u3 has no phone").isEqualTo(1);
    }

    @Test
    void aScheduledBroadcastCanBeCancelledAndNeverGoes() {
        BroadcastDtos.View later = service.create(request(List.of(BroadcastChannel.SMS), false, DAY.plusSeconds(7200), "Tomorrow"), ADA);
        assertThatThrownBy(() -> service.cancel(later.id(), new BroadcastDtos.CancelRequest(""), ADA)).isInstanceOf(BadRequestException.class);
        BroadcastDtos.View cancelled = service.cancel(later.id(), new BroadcastDtos.CancelRequest("Bridge reopened"), ADA);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledByName()).isEqualTo("Ada Ops");
        sender.useClock(Clock.fixed(DAY.plusSeconds(7300), ZoneOffset.UTC));
        assertThat(sender.sendNow(later.id())).isFalse();
        verify(sms, never()).send(anyString(), anyString());
        assertThatThrownBy(() -> service.cancel(later.id(), new BroadcastDtos.CancelRequest("again"), ADA)).hasMessageContaining("cancelled");
        assertThat(audit.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("BROADCAST", later.id())).extracting(NotificationOpsAudit::getAction)
                .containsExactly("BROADCAST_SCHEDULED", "BROADCAST_CANCELLED");
    }

    @Test
    void cancellingMidSendStopsBeforeTheNextRecipient() {
        BroadcastDtos.View created = service.create(request(List.of(BroadcastChannel.SMS), true, null, "Notice"), ADA);
        when(sms.send(anyString(), anyString())).thenAnswer(i -> {
            jdbc.update("update notification.broadcasts set status = 'CANCELLED' where id = ?", created.id());
            return true;
        });
        sender.sendNow(created.id());
        BroadcastDtos.View after = service.detail(created.id());
        assertThat(after.status()).isEqualTo("CANCELLED");
        verify(sms, times(1)).send(anyString(), anyString());
        assertThat(after.stats().sent()).isEqualTo(1);
        assertThat(after.stats().pending()).isZero();
    }

    @Test
    void onlySoManyBroadcastsCanBeCreatedInAnHour() {
        properties.setMaxPerHour(2);
        service.create(request(List.of(BroadcastChannel.SMS), false, null, "one"), ADA);
        service.create(request(List.of(BroadcastChannel.SMS), false, null, "two"), ADA);
        assertThatThrownBy(() -> service.create(request(List.of(BroadcastChannel.SMS), false, null, "three"), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("in an hour");
        assertThat(service.list(null, 0, 10).content()).hasSize(2);
        assertThat(service.list(List.of(Broadcast.Status.SENT), 0, 10).content()).isEmpty();
    }
}

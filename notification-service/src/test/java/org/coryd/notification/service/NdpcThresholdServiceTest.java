package org.coryd.notification.service;

import org.coryd.notification.client.PlatformStatsClient;
import org.coryd.notification.config.NdpcThresholdProperties;
import org.coryd.notification.entity.NdpcThresholdAlert;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.repository.NdpcThresholdAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One email per NDPC tier when it's near, one when it's reached - never daily, and never lost to a failed send. */
class NdpcThresholdServiceTest {

    private final NdpcThresholdProperties properties = new NdpcThresholdProperties();
    private final NdpcThresholdAlertRepository alerts = mock(NdpcThresholdAlertRepository.class);
    private final PlatformStatsClient stats = mock(PlatformStatsClient.class);
    private final EmailService email = mock(EmailService.class);
    private final NdpcThresholdService service = new NdpcThresholdService(properties, alerts, stats, email);
    private final List<NdpcThresholdAlert> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "recipients", List.of("ops@coryd.ng", " "));
        when(alerts.existsByThresholdAndKind(anyLong(), any())).thenAnswer(i -> sent.stream()
                .anyMatch(a -> a.getThreshold() == (long) i.getArgument(0) && a.getKind() == i.getArgument(1)));
        when(alerts.save(any())).thenAnswer(i -> {
            sent.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(email.sendHtml(anyList(), anyString(), anyString())).thenReturn(true);
    }

    private void people(long users, long waitlist) {
        when(stats.dataSubjects()).thenReturn(new PlatformStatsClient.DataSubjects(users, waitlist, users + waitlist));
    }

    @Test
    void belowEveryWarningPointNothingIsSent() {
        people(140, 19);

        service.check();

        verify(email, never()).sendHtml(anyList(), anyString(), anyString());
    }

    @Test
    void nearingTheFirstTierWarnsOnceThenReachingItSaysRegister() {
        people(150, 10);
        service.check();
        people(170, 10);
        service.check();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(1)).sendHtml(org.mockito.ArgumentMatchers.eq(List.of("ops@coryd.ng")), subject.capture(), html.capture());
        assertThat(subject.getValue()).isEqualTo("NDPC: CoRyd is nearing the Ordinary-High (MDP-OHL) tier (160 of 200 people)");
        assertThat(html.getValue()).contains("150 registered accounts + 10 beta waitlist").contains("register CoRyd with the NDPC")
                .contains("₦10,000");

        people(195, 5);
        service.check();
        verify(email).sendHtml(anyList(), org.mockito.ArgumentMatchers.eq("NDPC: CoRyd now holds data on 200 people - Ordinary-High (MDP-OHL) tier reached"), anyString());

        people(300, 5);
        service.check();
        verify(email, times(2)).sendHtml(anyList(), anyString(), anyString());
    }

    @Test
    void aBigJumpReportsEveryTierPassedInOneEmail() {
        people(1_150, 50);

        service.check();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(anyList(), subject.capture(), html.capture());
        assertThat(subject.getValue()).isEqualTo("NDPC: CoRyd now holds data on 1,200 people - Extra-High (MDP-EHL) tier reached");
        assertThat(html.getValue()).contains("Ordinary-High (MDP-OHL) reached").contains("Extra-High (MDP-EHL) reached")
                .contains("upgrade CoRyd's NDPC registration").doesNotContain("Ultra-High");
        assertThat(sent).extracting(NdpcThresholdAlert::getThreshold).containsExactlyInAnyOrder(200L, 1_000L);
    }

    @Test
    void theTopTierWarnsAt4000AndSaysUpgradeAt5000() {
        sent.add(NdpcThresholdAlert.builder().threshold(200).kind(NdpcThresholdAlert.Kind.REACHED).build());
        sent.add(NdpcThresholdAlert.builder().threshold(1_000).kind(NdpcThresholdAlert.Kind.REACHED).build());

        people(4_000, 0);
        service.check();
        verify(email).sendHtml(anyList(), org.mockito.ArgumentMatchers.eq("NDPC: CoRyd is nearing the Ultra-High (MDP-UHL) tier (4,000 of 5,000 people)"), anyString());

        people(5_000, 0);
        service.check();
        verify(email).sendHtml(anyList(), org.mockito.ArgumentMatchers.eq("NDPC: CoRyd now holds data on 5,000 people - Ultra-High (MDP-UHL) tier reached"), anyString());
    }

    @Test
    void aFailedSendIsTriedAgainNextRun() {
        people(210, 0);
        when(email.sendHtml(anyList(), anyString(), anyString())).thenReturn(false, true);

        service.check();
        assertThat(sent).isEmpty();
        service.check();

        assertThat(sent).hasSize(1);
        verify(email, times(2)).sendHtml(anyList(), anyString(), anyString());
    }

    @Test
    void noRecipientsOrNoCountMeansNothingIsRecorded() {
        people(210, 0);
        ReflectionTestUtils.setField(service, "recipients", List.of());
        service.check();

        when(stats.dataSubjects()).thenReturn(null);
        ReflectionTestUtils.setField(service, "recipients", List.of("ops@coryd.ng"));
        service.check();

        assertThat(sent).isEmpty();
        verify(email, never()).sendHtml(anyList(), anyString(), anyString());
    }

    @Test
    void theWarningPointIsAPercentageOfEachTier() {
        assertThat(properties.getTiers()).extracting(t -> t.warnAt(80)).containsExactly(160L, 800L, 4_000L);
        assertThat(properties.getTiers().get(0).warnAt(150)).isEqualTo(200L);
    }
}

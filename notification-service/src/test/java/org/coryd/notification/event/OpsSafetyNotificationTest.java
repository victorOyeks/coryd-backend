package org.coryd.notification.event;

import org.coryd.common.kafka.events.IssueReportUpdatedEvent;
import org.coryd.common.kafka.events.SosOpsRealertEvent;
import org.coryd.notification.config.SosAlertProperties;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessage;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.coryd.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Ops phase 5: an unacknowledged SOS re-alerts every ops phone and address once per event; a report's outcome reaches the reporter. */
class OpsSafetyNotificationTest {

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);

    private OpsSafetyNotificationListener listener(List<String> phones, List<String> emails) {
        SosAlertProperties properties = new SosAlertProperties();
        properties.setOpsPhones(phones);
        properties.setOpsEmails(emails);
        return new OpsSafetyNotificationListener(dispatch, properties);
    }

    private static SosOpsRealertEvent realert(String kind, String role) {
        return SosOpsRealertEvent.builder().incidentId("sos-1").kind(kind).triggeredByRole(role).routeSummary("Ikorodu → VI")
                .riderPhone("08011112222").driverName("Emeka").driverPhone("08053636321").vehiclePlate("LND-441-AA")
                .latitude(6.5).longitude(3.4).minutesOpen(15).level(2).build();
    }

    @Test
    void aReAlertGoesToEveryOpsPhoneByWhatsAppOrSmsAndEveryAddressByEmail() {
        listener(List.of("0800000001", " 0800000002 ", ""), List.of("ops@coryd.ng")).onSosRealert(realert("SOS", "RIDER"));

        ArgumentCaptor<WhatsAppMessage> whatsapp = ArgumentCaptor.forClass(WhatsAppMessage.class);
        verify(dispatch, times(2)).sendWhatsappOrSms(anyString(), anyString(), whatsapp.capture(), eq("SOS_OPS_REALERT"), eq(null));
        verify(dispatch).sendWhatsappOrSms(eq("0800000002"), anyString(), any(), eq("SOS_OPS_REALERT"), eq(null));
        assertThat(whatsapp.getValue().kind()).as("the already-approved ops SOS template").isEqualTo(WhatsAppMessageKind.SOS_OPS);
        assertThat(whatsapp.getValue().data().get("who")).contains("STILL UNACKNOWLEDGED after 15 min");
        verify(dispatch).sendEmail(eq("ops@coryd.ng"), eq("STILL UNACKNOWLEDGED after 15 min: the rider's SOS on Ikorodu → VI"),
                anyString(), eq("SOS_OPS_REALERT"));
    }

    @Test
    void theReAlertSmsIsPlainAndSaysWhatWasRaised() {
        String sms = OpsSafetyNotificationListener.realertSms(realert("SOS", "DRIVER"));
        assertThat(sms).startsWith("CoRyd SOS STILL UNACKNOWLEDGED after 15 min: the driver's SOS on Ikorodu to VI.")
                .contains("Driver Emeka 08053636321, car LND-441-AA").contains("https://maps.google.com/?q=6.5,3.4").doesNotContain("→");
        assertThat(OpsSafetyNotificationListener.realertSms(realert("SAFETY_CHECK", "SYSTEM"))).contains("an unanswered Are-you-OK check");
    }

    @Test
    void withNoOpsContactsConfiguredNothingIsSent() {
        listener(List.of(), List.of()).onSosRealert(realert("SOS", "RIDER"));
        verifyNoInteractions(dispatch);
    }

    @Test
    void aResolvedReportTellsTheReporterOpsMessageAndNothingElse() {
        OpsSafetyNotificationListener listener = listener(List.of(), List.of());
        listener.onIssueReportUpdated(IssueReportUpdatedEvent.builder().issueId("i-1").referenceNumber("ISS-00000042").reporterId("rider-1")
                .bookingId("b-1").status("RESOLVED").outcome("WARNING").message("We've spoken to the driver. Thank you.").build());
        verify(dispatch).sendPush("rider-1", "We've looked into report ISS-00000042", "We've spoken to the driver. Thank you.",
                "ISSUE_REPORT_UPDATED", "b-1");

        listener.onIssueReportUpdated(IssueReportUpdatedEvent.builder().issueId("i-2").reporterId("rider-2").status("DISMISSED")
                .message(" ").build());
        verify(dispatch, never()).sendPush(eq("rider-2"), anyString(), anyString(), anyString(), any());
    }
}

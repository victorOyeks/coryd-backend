package org.coryd.notification.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IssueReportUpdatedEvent;
import org.coryd.common.kafka.events.SosOpsRealertEvent;
import org.coryd.notification.config.KafkaConfig;
import org.coryd.notification.config.SosAlertProperties;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessage;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.coryd.notification.service.NotificationDispatchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Ops Console phase 5. An SOS (or unanswered safety check) nobody in ops has acknowledged re-alerts every ops phone and
 * address the same way the first alert did - WhatsApp with SMS fallback, and email (SOS_OPS_REALERT). A report ops
 * resolved or dismissed with a message tells the reporter by push and in their notification feed (ISSUE_REPORT_UPDATED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsSafetyNotificationListener {

    static final String REALERT = "SOS_OPS_REALERT";
    static final String REPORT_UPDATED = "ISSUE_REPORT_UPDATED";

    private final NotificationDispatchService dispatchService;
    private final SosAlertProperties sosAlertProperties;

    @KafkaListener(topics = KafkaTopics.SOS_OPS_REALERT, groupId = KafkaConfig.GROUP_ID_SAFETY)
    public void onSosRealert(SosOpsRealertEvent event) {
        List<String> phones = sosAlertProperties.phones();
        List<String> emails = sosAlertProperties.emails();
        if (phones.isEmpty() && emails.isEmpty()) {
            log.error("SOS {} unacknowledged after {} min - no ops phones or emails configured, ops not re-alerted",
                    event.getIncidentId(), event.getMinutesOpen());
            return;
        }
        String sms = realertSms(event);
        WhatsAppMessage whatsapp = realertWhatsapp(event);
        phones.forEach(phone -> dispatchService.sendWhatsappOrSms(phone, sms, whatsapp, REALERT, null));
        String subject = "STILL UNACKNOWLEDGED after " + event.getMinutesOpen() + " min: " + what(event) + " on " + orUnknown(event.getRouteSummary());
        String body = sms + "\n\nAcknowledge it in the Ops Console (Safety > SOS) so these alerts stop.\n\nIncident " + event.getIncidentId()
                + (event.getBookingId() != null ? ", booking " + event.getBookingId() : "")
                + (event.getRoutePostId() != null ? ", route " + event.getRoutePostId() : "");
        emails.forEach(email -> dispatchService.sendEmail(email, subject, body, REALERT));
    }

    /** Plain text (SMS can't carry arrows): what, how long, who to call, the car, the place. */
    static String realertSms(SosOpsRealertEvent event) {
        return "CoRyd SOS STILL UNACKNOWLEDGED after " + event.getMinutesOpen() + " min: " + what(event) + " on "
                + plain(orUnknown(event.getRouteSummary())) + "."
                + (event.getRiderPhone() != null ? " Rider " + event.getRiderPhone() + "." : "")
                + " Driver " + orUnknown(event.getDriverName()) + " " + orUnknown(event.getDriverPhone())
                + ", car " + orUnknown(event.getVehiclePlate()) + "."
                + (event.getLatitude() == null ? "" : " https://maps.google.com/?q=" + event.getLatitude() + "," + event.getLongitude());
    }

    /** The first alert's approved SOS_OPS template, its "who" saying this is a reminder - no new template to get approved. */
    static WhatsAppMessage realertWhatsapp(SosOpsRealertEvent event) {
        return new WhatsAppMessage(WhatsAppMessageKind.SOS_OPS, Map.of(
                "who", who(event) + " (STILL UNACKNOWLEDGED after " + event.getMinutesOpen() + " min)",
                "route", orUnknown(event.getRouteSummary()),
                "rider_phone", orUnknown(event.getRiderPhone()),
                "driver", orUnknown(event.getDriverName()),
                "driver_phone", orUnknown(event.getDriverPhone()),
                "car", orUnknown(event.getVehiclePlate()),
                "location", event.getLatitude() == null ? "not shared" : "https://maps.google.com/?q=" + event.getLatitude() + "," + event.getLongitude()));
    }

    @KafkaListener(topics = KafkaTopics.ISSUE_REPORT_UPDATED, groupId = KafkaConfig.GROUP_ID_ACCOUNT)
    public void onIssueReportUpdated(IssueReportUpdatedEvent event) {
        if (event.getReporterId() == null || event.getMessage() == null || event.getMessage().isBlank()) {
            return;
        }
        dispatchService.sendPush(event.getReporterId(), reportTitle(event), event.getMessage().trim(), REPORT_UPDATED, event.getBookingId());
    }

    static String reportTitle(IssueReportUpdatedEvent event) {
        String ref = event.getReferenceNumber() == null ? "your report" : "report " + event.getReferenceNumber();
        return "DISMISSED".equals(event.getStatus()) ? "Update on " + ref : "We've looked into " + ref;
    }

    private static String what(SosOpsRealertEvent event) {
        return "SAFETY_CHECK".equals(event.getKind()) ? "an unanswered Are-you-OK check" : "the " + who(event) + "'s SOS";
    }

    private static String who(SosOpsRealertEvent event) {
        return "DRIVER".equals(event.getTriggeredByRole()) ? "driver"
                : "SYSTEM".equals(event.getTriggeredByRole()) ? "safety check" : "rider";
    }

    private static String plain(String text) {
        return text.replace(" → ", " to ").replace("→", " to ");
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

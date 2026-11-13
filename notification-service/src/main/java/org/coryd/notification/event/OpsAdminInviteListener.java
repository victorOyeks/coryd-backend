package org.coryd.notification.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsAdminInvitedEvent;
import org.coryd.notification.config.KafkaConfig;
import org.coryd.notification.service.NotificationDispatchService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A super admin invited someone to the ops console - the set-password link goes out by email (logged as OPS_ADMIN_INVITE). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAdminInviteListener {

    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Lagos"));

    private final NotificationDispatchService dispatchService;

    @KafkaListener(topics = KafkaTopics.OPS_ADMIN_INVITED, groupId = KafkaConfig.GROUP_ID_ADMIN)
    public void onInvited(OpsAdminInvitedEvent event) {
        if (event.getEmail() == null || event.getLink() == null) {
            log.warn("Ops admin invite for {} has no email or link - not sent", event.getUserId());
            return;
        }
        dispatchService.sendEmail(event.getEmail(), subject(event), body(event), "OPS_ADMIN_INVITE");
    }

    static String subject(OpsAdminInvitedEvent event) {
        return event.isResent() ? "Your new invite to the CoRyd ops console" : "You're invited to the CoRyd ops console";
    }

    static String body(OpsAdminInvitedEvent event) {
        String hello = event.getFirstName() == null || event.getFirstName().isBlank() ? "Hello" : "Hello " + event.getFirstName();
        String by = event.getInvitedByName() == null || event.getInvitedByName().isBlank() ? "A CoRyd super admin" : event.getInvitedByName();
        return hello + ",\n\n"
                + by + " has invited you to the CoRyd ops console"
                + (event.getOpsRoleLabel() == null ? "" : " as " + event.getOpsRoleLabel()) + ".\n\n"
                + "Set your password to get started:\n" + event.getLink() + "\n\n"
                + "This link works once"
                + (event.getExpiresAt() == null ? "" : " and expires " + EXPIRES.format(event.getExpiresAt()) + " (Lagos time)") + "."
                + (event.isResent() ? " Any earlier invite link no longer works." : "") + "\n\n"
                + "If you weren't expecting this, you can ignore this email.\n\n- CoRyd";
    }
}

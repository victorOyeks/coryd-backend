package org.coryd.notification.event;

import org.coryd.common.kafka.events.OpsAdminInvitedEvent;
import org.coryd.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpsAdminInviteListenerTest {

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final OpsAdminInviteListener listener = new OpsAdminInviteListener(dispatch);

    @Test
    void emailsTheOneTimeLinkWithRoleAndExpiryInLagosTime() {
        OpsAdminInvitedEvent event = OpsAdminInvitedEvent.builder().userId("u-1").email("ngozi@coryd.ng").firstName("Ngozi")
                .opsRoleLabel("Finance").invitedByName("Ada Ops").link("https://admin.coryd.ng/accept-invite?token=abc")
                .expiresAt(Instant.parse("2026-11-16T09:00:00Z")).build();
        listener.onInvited(event);
        verify(dispatch).sendEmail(eq("ngozi@coryd.ng"), eq("You're invited to the CoRyd ops console"),
                contains("https://admin.coryd.ng/accept-invite?token=abc"), eq("OPS_ADMIN_INVITE"));
        String body = OpsAdminInviteListener.body(event);
        assertThat(body).contains("Hello Ngozi").contains("Ada Ops has invited you").contains("as Finance")
                .contains("expires Mon 16 Nov at 10:00 AM (Lagos time)");
    }

    @Test
    void aResentInviteSaysTheOldLinkIsDeadAndNothingIsSentWithoutAnAddress() {
        OpsAdminInvitedEvent resent = OpsAdminInvitedEvent.builder().email("a@b.c").link("https://x/accept-invite?token=t").resent(true).build();
        assertThat(OpsAdminInviteListener.subject(resent)).isEqualTo("Your new invite to the CoRyd ops console");
        assertThat(OpsAdminInviteListener.body(resent)).contains("Any earlier invite link no longer works");

        listener.onInvited(OpsAdminInvitedEvent.builder().userId("u-2").link("x").build());
        verify(dispatch, never()).sendEmail(eq(null), anyString(), anyString(), anyString());
    }
}

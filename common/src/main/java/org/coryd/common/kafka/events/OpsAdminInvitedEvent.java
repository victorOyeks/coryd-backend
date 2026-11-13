package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A super admin invited someone to the ops console (or sent their invite again) - notification-service emails the
 * set-password link. The link carries a one-time token (only its hash is stored); it stops working at expiresAt, once
 * used, or when a newer invite or a revoke replaces it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsAdminInvitedEvent {
    private String userId;
    private String email;
    private String firstName;
    /** Display label of the ops role they're invited as - "Finance". */
    private String opsRoleLabel;
    private String invitedByName;
    /** The console's set-password page with the one-time token. */
    private String link;
    private Instant expiresAt;
    /** True when this replaces an earlier invite. */
    private boolean resent;
}

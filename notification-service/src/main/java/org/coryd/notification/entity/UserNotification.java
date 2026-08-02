package org.coryd.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/**
 * The rider/driver-facing notification feed (the bell icon) - deliberately separate from
 * NotificationLog, which is a per-channel delivery audit row (SMS/push success/failure) and
 * has no concept of "read." One row here per logical notification, regardless of how many
 * devices it was pushed to.
 */
@Entity
@Table(name = "user_notifications", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification extends BaseEntity {

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(nullable = false, length = 100)
    private String eventType;

    /** Nullable - what "View details" deep-links to. Null for notification types with no single related booking (e.g. document expiring). */
    private String bookingId;

    @Builder.Default
    @Column(nullable = false)
    private boolean read = false;

    private Instant readAt;
}

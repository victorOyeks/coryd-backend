package org.coryd.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.NotificationChannel;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Entity
@Table(name = "notification_log", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog extends BaseEntity {

    @Column(length = 20)
    private String recipientPhone;

    @Column(length = 255)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false)
    private boolean delivered;

    /** Pushes only - whose device it went to, so one user's pushes can be traced. */
    @Column(length = 36)
    private String userId;

    /** Firebase's message id when it accepted the push. */
    @Column(length = 255)
    private String providerMessageId;

    /** Firebase's error code when it refused (UNREGISTERED, SENDER_ID_MISMATCH, ...). */
    @Column(length = 100)
    private String errorCode;
}

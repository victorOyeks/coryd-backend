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


/**
 * R-603/D-603: trip-critical notifications cannot be disabled - only the
 * promotional flag is user-controllable.
 */
@Entity
@Table(name = "notification_preferences", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferences extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String userId;

    @Builder.Default
    @Column(nullable = false)
    private boolean promotionalOptIn = true;

    /**
     * Whether texts CoRyd sends on this user's behalf (their trip shares and SOS alerts to their
     * emergency contacts) may go by WhatsApp. OTPs don't consult it - the user picks that channel
     * on the resend itself. Off only changes the channel - the same message still
     * goes out by SMS, so this never disables a trip-critical notification.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean whatsappOptIn = true;
}

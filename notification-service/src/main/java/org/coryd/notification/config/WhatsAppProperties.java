package org.coryd.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WhatsApp delivery (docs/whatsapp-notifications.md). Off unless WHATSAPP_ENABLED=true, and even then a
 * notification only goes by WhatsApp when its template id is set - a blank template id keeps that
 * notification on SMS. WhatsApp only lets a business start a conversation with an approved template, so
 * there is no free-text WhatsApp here: no template, no WhatsApp.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {

    private boolean enabled = false;

    /** Which WhatsAppProvider bean is used. Only "termii" exists today. */
    private String provider = "termii";

    private Termii termii = new Termii();

    private Templates templates = new Templates();

    /** The template id configured for a notification, or null when that notification stays on SMS. */
    public String templateId(WhatsAppMessageKind kind) {
        String id = switch (kind) {
            case OTP -> templates.getOtp();
            case TRIP_SHARE -> templates.getTripShare();
            case SOS_CONTACT -> templates.getSosContact();
            case SOS_OPS -> templates.getSosOps();
            case SAFETY_ESCALATED -> templates.getSafetyEscalated();
            case BROADCAST -> templates.getBroadcast();
        };
        return id == null || id.isBlank() ? null : id.trim();
    }

    @Getter
    @Setter
    public static class Termii {
        private String baseUrl = "https://api.ng.termii.com";
        private String apiKey;
        /** The WhatsApp device id from Termii's dashboard (Manage Device page) - not the SMS sender id. */
        private String deviceId;
    }

    /** Termii template ids, one per notification. Blank = that notification stays on SMS. */
    @Getter
    @Setter
    public static class Templates {
        private String otp;
        private String tripShare;
        private String sosContact;
        private String sosOps;
        private String safetyEscalated;
        private String broadcast;
    }
}

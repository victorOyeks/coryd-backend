package org.coryd.notification.gateway.whatsapp;

import java.util.Map;

/** One WhatsApp template message: which notification it is and the values for its placeholders. */
public record WhatsAppMessage(WhatsAppMessageKind kind, Map<String, String> data) {
}

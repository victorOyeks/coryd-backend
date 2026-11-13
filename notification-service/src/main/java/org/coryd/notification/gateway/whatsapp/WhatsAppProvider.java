package org.coryd.notification.gateway.whatsapp;

import java.util.Map;

/** A WhatsApp vendor. Swapping vendors means a new implementation selected by whatsapp.provider. */
public interface WhatsAppProvider {

    /**
     * Sends an approved template. Must not throw: a refusal, an HTTP error or a timeout comes back as a
     * failed result so the caller can fall back to SMS.
     *
     * @param phone international format without "+", e.g. 2348031234567
     */
    WhatsAppSendResult sendTemplate(String phone, String templateId, Map<String, String> data);
}

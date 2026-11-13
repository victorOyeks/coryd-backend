package org.coryd.notification.gateway.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.config.WhatsAppProperties;
import org.coryd.notification.gateway.PhoneNumbers;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WhatsApp delivery, provider-agnostic. Decides whether a message may go by WhatsApp at all (the
 * WHATSAPP_ENABLED flag and a template for that notification) and hands it to the configured
 * {@link WhatsAppProvider}. Choosing between WhatsApp and SMS, and recording the outcome, is
 * NotificationDispatchService's job.
 */
@Slf4j
@Service
public class WhatsAppService {

    private final WhatsAppProperties properties;
    private final WhatsAppProvider provider;

    public WhatsAppService(WhatsAppProperties properties, WhatsAppProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    /**
     * Sends the message, or returns empty without any outbound call when WhatsApp is off or the
     * notification has no template (the caller then uses SMS). Never throws.
     */
    public Optional<WhatsAppSendResult> send(String phone, WhatsAppMessage message) {
        String normalizedPhone = PhoneNumbers.toInternationalFormat(phone);
        if (!properties.isEnabled()) {
            log.info("whatsapp.enabled=false - not sending {} on WhatsApp to {}", message.kind(), normalizedPhone);
            return Optional.empty();
        }
        String templateId = properties.templateId(message.kind());
        if (templateId == null) {
            log.info("No WhatsApp template configured for {} - not sending on WhatsApp to {}", message.kind(), normalizedPhone);
            return Optional.empty();
        }
        try {
            return Optional.of(provider.sendTemplate(normalizedPhone, templateId, clean(message.data())));
        } catch (Exception e) {
            // Providers shouldn't throw, but one that does must still leave the SMS fallback reachable.
            log.error("WhatsApp provider threw for {} to {}: {}", message.kind(), normalizedPhone, e.getMessage());
            return Optional.of(WhatsAppSendResult.failed(e.getClass().getSimpleName()));
        }
    }

    /**
     * WhatsApp rejects a template whose parameter is empty or carries a newline, a tab or 4+ spaces in a
     * row - a value that would fail the whole send is flattened instead.
     */
    static Map<String, String> clean(Map<String, String> data) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (data == null) {
            return cleaned;
        }
        data.forEach((key, value) -> {
            String flat = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            cleaned.put(key, flat.isEmpty() ? "-" : flat);
        });
        return cleaned;
    }
}

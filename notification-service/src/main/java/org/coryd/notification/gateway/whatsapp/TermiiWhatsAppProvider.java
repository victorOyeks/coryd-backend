package org.coryd.notification.gateway.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.notification.config.WhatsAppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * WhatsApp through Termii's template API: POST {base}/api/send/template with
 * {phone_number, device_id, template_id, api_key, data} (https://developers.termii.com/templates).
 *
 * <p>Template-only on purpose. Termii's /api/sms/send also takes channel=whatsapp with free text, but WhatsApp
 * only delivers free text inside a 24h window the recipient opened by messaging us first - an emergency
 * contact or an ops phone never has, so free text would be refused or silently dropped exactly when it
 * matters. An approved template can start the conversation.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "whatsapp", name = "provider", havingValue = "termii", matchIfMissing = true)
public class TermiiWhatsAppProvider implements WhatsAppProvider {

    static final String SEND_TEMPLATE_PATH = "/api/send/template";

    private final RestClient restClient;
    private final String apiKey;
    private final String deviceId;

    @Autowired
    public TermiiWhatsAppProvider(WhatsAppProperties properties) {
        // Shorter read timeout than SMS: a WhatsApp send that hangs is followed by an SMS fallback,
        // and for an SOS the fallback must not wait long. Finite either way (see HttpTimeouts).
        this(HttpTimeouts.builder(Duration.ofSeconds(5), Duration.ofSeconds(10))
                .baseUrl(properties.getTermii().getBaseUrl())
                .build(), properties);
    }

    TermiiWhatsAppProvider(RestClient restClient, WhatsAppProperties properties) {
        this.restClient = restClient;
        this.apiKey = properties.getTermii().getApiKey();
        this.deviceId = properties.getTermii().getDeviceId();
    }

    @Override
    public WhatsAppSendResult sendTemplate(String phone, String templateId, Map<String, String> data) {
        if (deviceId == null || deviceId.isBlank()) {
            log.error("WhatsApp via Termii: WHATSAPP_TERMII_DEVICE_ID is not set - not sending template {} to {}", templateId, phone);
            return WhatsAppSendResult.failed("NO_DEVICE_ID");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("phone_number", phone);
        body.put("device_id", deviceId);
        body.put("template_id", templateId);
        body.put("api_key", apiKey);
        body.put("data", data);
        try {
            Map<String, Object> response = restClient.post()
                    .uri(SEND_TEMPLATE_PATH)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return interpret(response, phone, templateId);
        } catch (RestClientResponseException e) {
            log.error("WhatsApp via Termii refused template {} to {}: HTTP {} {}", templateId, phone,
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            return WhatsAppSendResult.failed(truncate("HTTP_" + e.getStatusCode().value()));
        } catch (Exception e) {
            log.error("WhatsApp via Termii failed for template {} to {}: {}", templateId, phone, e.getMessage());
            return WhatsAppSendResult.failed(truncate(e.getClass().getSimpleName()));
        }
    }

    /** Termii answers {"code":"ok","message_id":"...","message":"Successfully Sent",...} when it takes the message. */
    private static WhatsAppSendResult interpret(Map<String, Object> response, String phone, String templateId) {
        if (response == null) {
            log.error("WhatsApp via Termii: empty response for template {} to {}", templateId, phone);
            return WhatsAppSendResult.failed("EMPTY_RESPONSE");
        }
        Object code = response.get("code");
        Object messageId = response.get("message_id_str") != null ? response.get("message_id_str") : response.get("message_id");
        if ("ok".equalsIgnoreCase(String.valueOf(code)) || (code == null && messageId != null)) {
            return WhatsAppSendResult.accepted(messageId == null ? null : String.valueOf(messageId));
        }
        log.error("WhatsApp via Termii not accepted for template {} to {}: {}", templateId, phone, response);
        Object reason = code != null ? code : response.get("message");
        return WhatsAppSendResult.failed(truncate(reason == null ? "NOT_ACCEPTED" : String.valueOf(reason)));
    }

    private static String truncate(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100);
    }
}

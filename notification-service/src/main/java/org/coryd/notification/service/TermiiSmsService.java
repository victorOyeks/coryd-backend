package org.coryd.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin wrapper over Termii's SMS API. Kept behind a single method so swapping
 * SMS vendors later only touches this class.
 */
@Slf4j
@Service
public class TermiiSmsService {

    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String senderId;

    public TermiiSmsService(@Value("${termii.base-url}") String baseUrl,
                             @Value("${termii.enabled:false}") boolean enabled,
                             @Value("${termii.api-key}") String apiKey,
                             @Value("${termii.sender-id}") String senderId) {
        this.restClient = RestClient.create(baseUrl);
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.senderId = senderId;
    }

    public boolean send(String toPhone, String message) {
        // "generic" is Termii's promotional route - it never reaches a number registered on
        // Nigeria's DND list, and Termii still marks it "SENT" even when the telco silently drops
        // it. OTPs/transactional messages need "dnd", which bypasses that block.
        return dispatch(toPhone, message, "dnd");
    }

    public boolean sendWhatsapp(String toPhone, String message) {
        return dispatch(toPhone, message, "whatsapp");
    }

    /** Separate endpoint from send()/sendWhatsapp() - Termii's voice OTP API takes a bare numeric
     * code (it generates and reads out its own script), not a full message, and needs no sender ID
     * since it's a phone call, not a text with a displayed "from" name. Stopgap while the DND route
     * is pending Termii's approval - a code read aloud isn't blocked by the SMS DND registry. */
    public boolean sendVoiceOtp(String toPhone, String code) {
        String normalizedPhone = toInternationalFormat(toPhone);
        if (!enabled) {
            log.info("termii.enabled=false - not placing voice OTP call to {}", normalizedPhone);
            return true;
        }
        try {
            restClient.post()
                    .uri("/api/sms/otp/send/voice")
                    .body(Map.of(
                            "api_key", apiKey,
                            "phone_number", normalizedPhone,
                            "code", code
                    ))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("Failed to place voice OTP call to {}: {}", normalizedPhone, e.getMessage());
            return false;
        }
    }

    private boolean dispatch(String toPhone, String message, String channel) {
        String normalizedPhone = toInternationalFormat(toPhone);
        if (!enabled) {
            log.info("termii.enabled=false - not sending {} to {}: {}", channel, normalizedPhone, message);
            return true;
        }
        try {
            restClient.post()
                    .uri("/api/sms/send")
                    .body(Map.of(
                            "api_key", apiKey,
                            "to", normalizedPhone,
                            "from", senderId,
                            "sms", message,
                            "type", "plain",
                            "channel", channel
                    ))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("Failed to send {} message to {}: {}", channel, normalizedPhone, e.getMessage());
            return false;
        }
    }

    /** Every phone number in this codebase is stored/passed around in local Nigerian format
     * (0803...) - Termii rejects that outright and needs international format with no leading
     * zero and no "+" (234803...). Converted only here, right before the API call, so nothing
     * else (storage, login, lookups) has to change format. */
    private static String toInternationalFormat(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 11) {
            return "234" + digits.substring(1);
        }
        return digits;
    }
}

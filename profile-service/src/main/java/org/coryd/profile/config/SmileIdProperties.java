package org.coryd.profile.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Smile ID Biometric KYC - the NIN + live selfie check everyone passes before they ride or drive.
 * The app captures the selfie with Smile ID's SDK and submits it straight to Smile ID; we only open
 * the job (with a short-lived token minted from the API key) and read the result off the webhook.
 * The API key never leaves the backend. Sandbox and live are different hosts and different keys.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "smile-id")
public class SmileIdProperties {

    /** Numeric, no leading zeros - from the Smile ID portal. Blank means identity checks can't start. */
    private String partnerId;

    /** Mints the app's tokens and signs every webhook, so it also verifies them. Never sent to the app. */
    private String apiKey;

    /** CoRyd runs on live (api.smileidentity.com). true points at testapi.smileidentity.com, where only a sandbox key works. */
    private boolean sandbox = false;

    /** Blank uses the default set for this environment in the Smile ID portal. */
    private String callbackUrl;

    /** NIN_V2 needs an NIMC enterprise ID on the Smile ID account; NIN_SLIP is the fallback. */
    private String idType = "NIN_V2";

    /**
     * How close the NIMC name has to be to the profile name, 0-1. NIMC returns names uppercased and
     * sometimes with a middle name attached, so an exact match is too strict; 0.85 tolerates spelling
     * drift without letting a different person through.
     */
    private double nameMatchThreshold = 0.85;

    public boolean isConfigured() {
        return partnerId != null && !partnerId.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public String baseUrl() {
        return sandbox ? "https://testapi.smileidentity.com" : "https://api.smileidentity.com";
    }
}

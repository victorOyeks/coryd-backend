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

    /**
     * A name below the threshold that still shares one name with the record (a married name, a dropped middle
     * name): APPROVE lets them through with the check marked PARTIAL_NAME_MATCH for ops to look at afterwards,
     * OPS holds them until ops decides, as any other mismatch.
     */
    private PartialNameMatch partialNameMatch = PartialNameMatch.APPROVE;

    public enum PartialNameMatch { APPROVE, OPS }

    public boolean approvesPartialNames() {
        return partialNameMatch == PartialNameMatch.APPROVE;
    }

    /**
     * Sandbox only: the Smile ID test identity every check is sent as, so every sandbox check succeeds.
     * Smile ID's sandbox only answers for its test identities (matched on these three fields), and this
     * one comes back "clear" for both Biometric KYC and Document Verification. Never used on live.
     */
    private TestIdentity testIdentity = new TestIdentity();

    /**
     * Drivers prove who they are with a NIN lookup (number only, no selfie) plus their licence check, instead of
     * the NIN + selfie check riders do - one face match per person, not two. false keeps the older flow (drivers
     * do the NIN + selfie check) for app builds that don't have the lookup yet.
     */
    private boolean driversUseNinLookup = true;

    /** Each lookup is billed, found or not - three is plenty for a real NIN with a typo. */
    private int ninLookupMaxAttempts = 3;

    /** A pause between lookups, so a double tap or an impatient retry isn't billed twice. */
    private int ninLookupCooldownSeconds = 30;

    /**
     * Keys the one-way fingerprint of a NIN, so the same NIN on two accounts (or a number already found not to
     * exist) is caught without keeping the NIN itself. Must never change once set, or old fingerprints stop matching.
     */
    private String ninFingerprintKey;

    /** The privacy notice the person agreed to, sent with each lookup as Smile ID requires. */
    private String privacyNoticeUrl = "https://coryd.ng/privacy";

    @Getter
    @Setter
    public static class TestIdentity {
        private String givenNames = "Amina Fatou";
        private String lastName = "Clearwater";
        private String email = "amina.clearwater@example.com";
    }

    /** Drivers whose NIN check is the lookup, not the NIN + selfie check. */
    public boolean driversLookUpNin() {
        return driversUseNinLookup;
    }

    public boolean isConfigured() {
        return partnerId != null && !partnerId.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** Only for pointing a local run at a stand-in for Smile ID - blank in every real environment. */
    private String baseUrlOverride;

    public String baseUrl() {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return baseUrlOverride;
        }
        return sandbox ? "https://testapi.smileidentity.com" : "https://api.smileidentity.com";
    }
}

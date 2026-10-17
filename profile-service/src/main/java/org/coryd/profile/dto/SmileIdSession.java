package org.coryd.profile.dto;

import java.util.Map;

/**
 * Everything the app passes to Smile ID's SDK for one check. token is short-lived - start the SDK straight
 * away. partnerParams goes on partnerConfig.partnerParams exactly as given: it carries checkId, which is how
 * the result finds its way back to this check (Smile ID issues its own job and user IDs - don't set them).
 * callbackUrl is null when the Smile ID portal's URL is used; leave it unset then. idType goes in
 * biometricKYCParams (with the NIN as idNumber) for a NIN check, in documentVerificationParams for a licence.
 */
public record SmileIdSession(
        String checkId,
        String token,
        String partnerId,
        boolean sandbox,
        String callbackUrl,
        String country,
        String idType,
        Map<String, String> partnerParams
) {
    public static final String CHECK_ID_PARAM = "coryd_check_id";
}

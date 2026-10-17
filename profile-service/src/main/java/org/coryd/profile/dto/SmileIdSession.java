package org.coryd.profile.dto;

/**
 * Everything the app passes to Smile ID's SDK for one check. token is short-lived - start the SDK
 * straight away. callbackUrl is null when the Smile ID portal's default is used; leave it unset then.
 * idInfo for the SDK is { country, idType, idNumber: the NIN they just entered }.
 */
public record SmileIdSession(
        String jobId,
        String userId,
        String token,
        String partnerId,
        boolean sandbox,
        String callbackUrl,
        String country,
        String idType
) {
}

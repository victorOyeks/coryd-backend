package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverProfile;

/** Lightweight, search-result-safe projection of DriverProfile - deliberately excludes governmentIdUrl. */
public record DriverSummary(String userId, String firstName, String lastName, String photoUrl,
                            VerificationStatus verificationStatus) {

    public static DriverSummary from(DriverProfile profile) {
        return new DriverSummary(profile.getUserId(), profile.getFirstName(), profile.getLastName(),
                profile.getPhotoUrl(), identityStatus(profile));
    }

    /** Identity-only signal (government ID) for display - not the verification gate that RouteService.post() checks. */
    private static VerificationStatus identityStatus(DriverProfile profile) {
        if (profile.getGovernmentIdStatus() == VerificationStatus.REJECTED) {
            return VerificationStatus.REJECTED;
        }
        if (profile.getGovernmentIdStatus() == VerificationStatus.APPROVED) {
            return VerificationStatus.APPROVED;
        }
        return VerificationStatus.PENDING;
    }
}

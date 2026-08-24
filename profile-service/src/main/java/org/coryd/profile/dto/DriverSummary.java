package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverProfile;

/** Lightweight, search-result-safe projection of DriverProfile - deliberately excludes governmentIdUrl. */
public record DriverSummary(String userId, String legalName, String photoUrl, VerificationStatus verificationStatus) {

    public static DriverSummary from(DriverProfile profile) {
        return new DriverSummary(profile.getUserId(), profile.getLegalName(), profile.getPhotoUrl(), identityStatus(profile));
    }

    /** Identity-only signal (government ID + selfie) for the rider-facing "verified driver" badge - not the full six-item verification gate that RouteService.post() checks. */
    private static VerificationStatus identityStatus(DriverProfile profile) {
        if (profile.getGovernmentIdStatus() == VerificationStatus.REJECTED || profile.getSelfieStatus() == VerificationStatus.REJECTED) {
            return VerificationStatus.REJECTED;
        }
        if (profile.getGovernmentIdStatus() == VerificationStatus.APPROVED && profile.getSelfieStatus() == VerificationStatus.APPROVED) {
            return VerificationStatus.APPROVED;
        }
        return VerificationStatus.PENDING;
    }
}

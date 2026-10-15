package org.coryd.identity.dto;

public record OnboardingStatusResponse(
        boolean phoneVerified,
        boolean passwordSet,
        boolean profileComplete,
        boolean termsAccepted,
        /** Riders and drivers: NIN + selfie check approved - nobody books or posts until it is. */
        boolean identityVerified,
        /** PENDING (not submitted - show the NIN step), IN_REVIEW, APPROVED or REJECTED. */
        String identityVerificationStatus
) {
}

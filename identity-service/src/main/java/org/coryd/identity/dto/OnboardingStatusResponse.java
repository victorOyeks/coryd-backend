package org.coryd.identity.dto;

public record OnboardingStatusResponse(
        boolean phoneVerified,
        boolean passwordSet,
        boolean profileComplete,
        boolean termsAccepted
) {
}

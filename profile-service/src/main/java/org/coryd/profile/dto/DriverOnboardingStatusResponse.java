package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;

/**
 * Driver-side equivalent of identity-service's rider OnboardingStatusResponse - same simple
 * per-step boolean shape, so the driver app can resume onboarding at the right screen the same
 * way the rider app already does.
 *
 * Reflects submission, not approval - a step the driver has submitted but ops hasn't reviewed
 * yet still reads true here, since the driver's own part of it is done and there's nothing left
 * for them to "attend to" on that step. overallStatus is the separate approval signal (whether
 * they can actually start posting routes yet), same value VerificationAggregationService.getStatus()
 * and the route-posting gate already use.
 */
public record DriverOnboardingStatusResponse(
        boolean profileSubmitted,
        boolean governmentIdSubmitted,
        boolean selfieSubmitted,
        boolean vehicleSubmitted,
        boolean documentsSubmitted,
        VerificationStatus overallStatus
) {
}

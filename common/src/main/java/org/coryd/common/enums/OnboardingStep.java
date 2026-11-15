package org.coryd.common.enums;

/**
 * The next thing a rider or driver has to do to finish signing up - what an onboarding reminder names, and the "step" the
 * app deep-links to from it (docs/ops-console/onboarding-reminders.md). Listed in the order they are asked for: a reminder
 * names the first one outstanding.
 */
public enum OnboardingStep {
    /** Fill in their profile (riders: create it; drivers: name and date of birth). */
    PROFILE,
    /** Accept the terms of service. */
    TERMS,
    /** Verify their NIN (NIN + live selfie, or a driver's NIN lookup) - not started, left mid-way, or Smile ID / ops asked them to try again. */
    NIN,
    /** Every NIN try used - only CoRyd support can let them try again. */
    NIN_LOCKED_OUT,
    /** Drivers: their driver's licence. */
    LICENCE,
    /** Drivers: vehicle details and registration. */
    VEHICLE,
    /** Drivers: insurance certificate. */
    INSURANCE,
    /** Drivers: roadworthiness certificate. */
    ROADWORTHINESS;

    /** Why the step is outstanding - picks the wording of the reminder. */
    public enum Reason {
        /** Never done (or left half-way). */
        NOT_DONE,
        /** Smile ID or ops asked them to try again. */
        RETRY,
        /** Ops asked them to upload it again (a resubmission request). */
        RESUBMIT,
        /** Ops rejected it - they need to send a new one. */
        REJECTED,
        /** It expired before they were approved. */
        EXPIRED,
        /** Locked out - contact support. */
        LOCKED_OUT
    }
}

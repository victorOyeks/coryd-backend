package org.coryd.profile.entity;

/** The Smile ID checks CoRyd runs. */
public enum SmileIdCheckType {
    /** Everyone: NIN looked up with NIMC, live selfie matched to the NIN photo (Biometric KYC). */
    NIN,
    /** Drivers: licence authenticated and read, live selfie matched to the licence photo (Document Verification). */
    DRIVERS_LICENCE,
    /** Drivers: NIN looked up with NIMC by number only, no selfie (Enhanced KYC) - sent by the backend, not the app's SDK. */
    NIN_LOOKUP
}

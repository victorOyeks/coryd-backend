package org.coryd.profile.entity;

/** Why an automatic Smile ID check needs another go - shown to the person with a Try again button. */
public enum SmileIdRetryReason {
    /** The selfie isn't the face on the NIN record or the licence. */
    FACE_MISMATCH,
    /** NIMC has no record of this NIN - usually a typo. NIN check only. */
    NIN_NOT_FOUND,
    /** The photo wasn't a Nigerian driver's licence Smile ID accepts. Licence check only. */
    NOT_A_LICENCE,
    /** The selfie or licence photo couldn't be used - blurry, glare, dark, cropped. */
    UNREADABLE,
    /** Smile ID or NIMC was down; nothing was wrong with what they sent. */
    TRY_LATER
}

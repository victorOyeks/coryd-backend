package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationCheckOutcome;

import java.time.Instant;

/**
 * One person awaiting an ops decision, and what the automatic check said (null if none ran). The selfie
 * is in the Smile ID portal under the job named in checkSummary; selfieUrl is only set by the older selfie upload.
 */
public record IdentityVerificationQueueItem(
        String userId,
        String firstName,
        String lastName,
        String ninLast4,
        String selfieUrl,
        Instant submittedAt,
        int attempts,
        VerificationCheckOutcome checkOutcome,
        String checkSummary
) {
    /** "•••• 5040" - how the console shows the NIN (only the last four digits are kept). See docs/ops-console/privacy.md. */
    @com.fasterxml.jackson.annotation.JsonProperty("ninMasked")
    public String ninMasked() {
        return org.coryd.common.util.PiiMask.nin(ninLast4);
    }

}

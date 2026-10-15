package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationCheckOutcome;

import java.time.Instant;

/** One rider awaiting an ops decision - the selfie to look at, and what Prembly said (null if it hasn't run). */
public record IdentityVerificationQueueItem(
        String userId,
        String firstName,
        String lastName,
        String ninLast4,
        String selfieUrl,
        Instant submittedAt,
        int attempts,
        VerificationCheckOutcome premblyOutcome,
        String premblySummary
) {
}

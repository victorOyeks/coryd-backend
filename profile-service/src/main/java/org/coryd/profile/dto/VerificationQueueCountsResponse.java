package org.coryd.profile.dto;

public record VerificationQueueCountsResponse(
        long all,
        long pending,
        long inReview,
        long resubmitted
) {
}

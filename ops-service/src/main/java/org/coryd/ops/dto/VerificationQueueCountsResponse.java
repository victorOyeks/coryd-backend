package org.coryd.ops.dto;

public record VerificationQueueCountsResponse(
        long all,
        long pending,
        long inReview,
        long resubmitted
) {
}

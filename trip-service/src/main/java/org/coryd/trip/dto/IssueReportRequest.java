package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.IssueCategory;


public record IssueReportRequest(
        /** Null when reported from Account > Report an issue with no trip selected. */
        String bookingId,
        @NotNull String reportedBy,
        @NotNull IssueCategory category,
        @NotBlank String description
) {
}

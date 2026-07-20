package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.IssueCategory;


public record IssueReportRequest(
        @NotNull String reportedBy,
        @NotNull IssueCategory category,
        @NotBlank String description
) {
}

package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.coryd.common.enums.IssueCategory;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueReportedEvent {
    private String issueId;
    private String bookingId;
    private String reportedBy;
    private IssueCategory category;
    private String referenceNumber;
    private LocalDateTime reportedAt;
}

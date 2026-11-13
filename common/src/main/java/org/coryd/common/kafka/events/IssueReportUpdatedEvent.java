package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ops resolved or dismissed a report - the reporter gets ops' message. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueReportUpdatedEvent {
    private String issueId;
    private String referenceNumber;
    private String reporterId;
    private String bookingId;
    /** RESOLVED or DISMISSED. */
    private String status;
    private String outcome;
    /** What the reporter reads. Never the internal note. */
    private String message;
}

package org.coryd.trip.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportActionType;
import org.coryd.common.enums.IssueReportOutcome;

import java.time.Instant;


@Entity
@Table(name = "issue_reports", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IssueReport extends BaseEntity {

    /** Nullable - the account-level "Report an issue" flow lets a rider report something with no trip selected. */
    private String bookingId;

    @Column(nullable = false)
    private String reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IssueCategory category;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, unique = true, length = 20)
    private String referenceNumber;

    /** IssueReportStatus: OPEN, IN_PROGRESS, RESOLVED, DISMISSED. Text so the apps' response is unchanged. */
    @Column(nullable = false, length = 20)
    private String status;

    // ---- the Ops Console's handling - JSON-ignored so the app's "report sent" response stays what it was ----------

    /** RIDER or DRIVER - the side of the trip the reporter was on; null for an account-level report from a rider app. */
    @JsonIgnore
    @Column(length = 10)
    private String reporterRole;

    @JsonIgnore
    @Column(length = 36)
    private String assignedTo;
    @JsonIgnore
    @Column(length = 120)
    private String assignedToName;
    @JsonIgnore
    private Instant assignedAt;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private IssueReportOutcome outcome;
    @JsonIgnore
    @Column(length = 1000)
    private String messageToReporter;
    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private IssueReportActionType actionType;
    @JsonIgnore
    @Column(length = 100)
    private String actionReference;

    @JsonIgnore
    private Instant resolvedAt;
    @JsonIgnore
    @Column(length = 36)
    private String resolvedBy;
    @JsonIgnore
    @Column(length = 120)
    private String resolvedByName;
}

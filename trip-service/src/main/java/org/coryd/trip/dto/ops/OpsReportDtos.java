package org.coryd.trip.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportActionType;
import org.coryd.common.enums.IssueReportOutcome;
import org.coryd.common.enums.IssueReportStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Reports & disputes as trip-service serves them to ops-service (internal only). */
public final class OpsReportDtos {

    private OpsReportDtos() {
    }

    public record Filter(IssueReportStatus status, IssueCategory category, String reporterRole, LocalDate from, LocalDate to,
                         String routePostId, String assignedTo, String userId, String search) {
    }

    public record Row(String id, String referenceNumber, IssueReportStatus status, IssueCategory category, String description,
                      String reporterId, String reporterRole, String bookingId, String riderId, String routePostId, String driverId,
                      String originLabel, String destinationLabel, Instant departureTime, Instant createdAt,
                      String assignedTo, String assignedToName, Instant assignedAt,
                      IssueReportOutcome outcome, String messageToReporter, IssueReportActionType actionType, String actionReference,
                      Instant resolvedAt, String resolvedBy, String resolvedByName) {
    }

    public record Counts(long all, long open, long inProgress, long resolved, long dismissed) {
    }

    public record Booking(String bookingId, BookingStatus status, Integer seatCount, BigDecimal fareTotal, String pickupLabel,
                          String dropoffLabel, Instant confirmedAt) {
    }

    public record Allowed(boolean assign, boolean note, boolean resolve, boolean dismiss) {
    }

    public record Detail(Row report, Booking booking, List<String> attachments, List<Row> priorReports, List<OpsAuditEntry> actions,
                         Allowed allowed) {
    }

    public record ActionResult(String action, String auditId, IssueReportStatus statusBefore, IssueReportStatus statusAfter, Row report) {
    }

    /** assigneeId defaults to the admin acting. */
    public record AssignRequest(String assigneeId, @Size(max = 120) String assigneeName, @Size(max = 1000) String note,
                                @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record NoteRequest(@NotBlank(message = "is required") @Size(max = 1000) String note,
                              @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record ResolveRequest(@NotNull(message = "is required") IssueReportOutcome outcome,
                                 @NotBlank(message = "is required") @Size(max = 1000) String note,
                                 @NotBlank(message = "is required - the reporter reads it") @Size(max = 1000) String messageToReporter,
                                 IssueReportActionType actionType, @Size(max = 100) String actionReference,
                                 @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record DismissRequest(@NotBlank(message = "is required") @Size(max = 1000) String note,
                                 @Size(max = 1000) String messageToReporter,
                                 @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }
}

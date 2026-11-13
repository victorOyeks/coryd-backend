package org.coryd.ops.dto.safety;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.IssueReportActionType;
import org.coryd.common.enums.IssueReportOutcome;
import org.coryd.common.enums.SosEscalationTarget;
import org.coryd.common.enums.SosOutcome;

import java.time.Instant;

/** What the console sends for safety & support actions. The admin is never sent - it's the signed-in token. */
public final class SafetyRequests {

    private SafetyRequests() {
    }

    public record SosAcknowledge(@Size(max = 1000) String note) {
    }

    public record SosNote(@NotBlank(message = "is required") @Size(max = 1000) String note) {
    }

    public record SosEscalate(@NotNull(message = "is required - POLICE, AMBULANCE, FIRE_SERVICE, EMERGENCY_LINE, ROAD_SAFETY, EMERGENCY_CONTACT or OTHER")
                              SosEscalationTarget target,
                              @Size(max = 200) String contact, Instant contactedAt, @Size(max = 100) String reference,
                              @Size(max = 1000) String note) {
    }

    public record SosResolve(@NotNull(message = "is required - SAFE, FALSE_ALARM, ESCALATED_TO_AUTHORITIES or OTHER") SosOutcome outcome,
                             @NotBlank(message = "is required") @Size(max = 1000) String note) {
    }

    public record ReportAssign(String assigneeId, @Size(max = 120) String assigneeName, @Size(max = 1000) String note) {
    }

    public record ReportNote(@NotBlank(message = "is required") @Size(max = 1000) String note) {
    }

    public record ReportResolve(@NotNull(message = "is required - NO_ACTION, WARNING, SUSPENDED, REFUND_ISSUED or OTHER") IssueReportOutcome outcome,
                                @NotBlank(message = "is required") @Size(max = 1000) String note,
                                @NotBlank(message = "is required - the reporter reads it") @Size(max = 1000) String messageToReporter,
                                IssueReportActionType actionType, @Size(max = 100) String actionReference) {
    }

    public record ReportDismiss(@NotBlank(message = "is required") @Size(max = 1000) String note,
                                @Size(max = 1000) String messageToReporter) {
    }

    public record RatingModeration(@NotBlank(message = "is required") @Size(max = 500) String reason) {
    }
}

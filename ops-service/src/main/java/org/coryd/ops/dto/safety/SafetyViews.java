package org.coryd.ops.dto.safety;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportActionType;
import org.coryd.common.enums.IssueReportOutcome;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.SosEscalationTarget;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosOutcome;
import org.coryd.common.enums.SosStatus;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.RiderSummary;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.TripLocation;
import org.coryd.ops.dto.VehicleSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the console reads for Safety & SOS, Reports & disputes and Ratings - see docs/ops-console/safety-support.md. */
public final class SafetyViews {

    private SafetyViews() {
    }

    /** An admin who did something - id and display name. */
    public record Admin(String id, String name) {
    }

    // ---- SOS ---------------------------------------------------------------------------------------------------------

    public record Escalation(Instant recordedAt, SosEscalationTarget target, String contact, Instant contactedAt, String reference) {
    }

    public record Resolution(Instant at, Admin by, SosOutcome outcome, String note) {
    }

    public record SosItem(String id, String ref, SosKind kind, SosStatus status, Instant raisedAt, Long minutesOpen,
                          PersonSummary raisedBy, String safetyCheckKind, String safetyAlertId,
                          String routePostId, String tripRef, String bookingId, String bookingRef,
                          String originLabel, String destinationLabel, RouteStatus routeStatus, Instant departureTime,
                          DriverSummary driver, RiderSummary rider, VehicleSummary vehicle,
                          Double latitude, Double longitude, Instant opsNotifiedAt,
                          Instant acknowledgedAt, Admin acknowledgedBy, Escalation escalation, Resolution resolution,
                          int realertsSent) {
    }

    public record SosCounts(long all, long open, long acknowledged, long escalated, long resolved) {
    }

    /** One delivery of an alert: WHATSAPP, SMS (WhatsApp refused or off), EMAIL or PUSH, and whether the provider took it. */
    public record Delivery(Instant at, String channel, boolean delivered, String errorCode) {
    }

    /** An emergency contact the SOS texted - deliveries null when notification-service couldn't be asked. */
    public record ContactNotified(String name, String phone, List<Delivery> deliveries) {
    }

    /** An ops phone or address alerted - the first alert (SOS_OPS_ALERT / SAFETY_ESCALATED) and each re-alert (SOS_OPS_REALERT). */
    public record OpsAlert(Instant at, String eventType, String channel, String recipient, boolean delivered, String errorCode) {
    }

    public record SafetyAnswer(String userId, String role, String name, String phone, String answer) {
    }

    public record SafetyCheck(String alertId, String kind, String status, Instant raisedAt, Instant escalatedAt, Double latitude,
                              Double longitude, List<SafetyAnswer> answers) {
    }

    public record Location(TripLocation live, TripLocation lastSeen, Long ageSeconds) {
    }

    /** One line of the incident's own story, oldest first. */
    public record TimelineEntry(Instant at, String type, String actor, String actorId, String actorName, String detail) {
    }

    public record AuditEntry(String id, Instant at, Admin by, String action, String note, String before, String after) {
    }

    public record SosAllowed(boolean acknowledge, boolean note, boolean escalate, boolean resolve) {
    }

    public record SosDetail(SosItem incident, Location location, List<RiderSummary> ridersOnTrip, List<ContactNotified> contactsNotified,
                            List<OpsAlert> opsAlerts, SafetyCheck safetyCheck, List<SafetyCheck> tripSafetyChecks,
                            List<TimelineEntry> timeline, List<AuditEntry> actions, SosAllowed allowed, TripDetailResponse trip) {
    }

    public record SosActionResult(String action, String auditId, SosStatus statusBefore, SosStatus statusAfter, SosItem incident) {
    }

    // ---- reports -----------------------------------------------------------------------------------------------------

    public record ReportItem(String id, String referenceNumber, IssueReportStatus status, IssueCategory category, String description,
                             PersonSummary reporter, String bookingId, String bookingRef, String routePostId, String tripRef,
                             String originLabel, String destinationLabel, Instant departureTime,
                             DriverSummary driver, RiderSummary rider, Instant createdAt,
                             Admin assignedTo, Instant assignedAt, IssueReportOutcome outcome, String messageToReporter,
                             IssueReportActionType actionType, String actionReference, Instant resolvedAt, Admin resolvedBy) {
    }

    public record ReportCounts(long all, long open, long inProgress, long resolved, long dismissed) {
    }

    public record ReportBooking(String bookingId, String bookingRef, BookingStatus status, Integer seatCount, BigDecimal fareTotal,
                                String pickupLabel, String dropoffLabel, Instant confirmedAt) {
    }

    /** A prior report and which of this report's people it involves (filed by them, or about a trip they were on). */
    public record PriorReport(ReportItem report, List<String> involves) {
    }

    /** An existing ops action the console can offer from the report - done there, then referenced on resolve. */
    public record RelatedAction(String type, String method, String path, IssueReportActionType referenceAs, String referenceHint) {
    }

    public record ReportAllowed(boolean assign, boolean note, boolean resolve, boolean dismiss) {
    }

    public record ReportDetail(ReportItem report, ReportBooking booking, List<String> attachments, List<PriorReport> priorReports,
                               Map<String, Long> priorReportCounts, List<AuditEntry> actions, List<RelatedAction> relatedActions,
                               ReportAllowed allowed) {
    }

    public record ReportActionResult(String action, String auditId, IssueReportStatus statusBefore, IssueReportStatus statusAfter,
                                     ReportItem report) {
    }

    // ---- ratings -----------------------------------------------------------------------------------------------------

    public record RatingItem(String id, String bookingId, String bookingRef, String routePostId, String tripRef, RatingDirection direction,
                             int stars, String comment, Instant createdAt, PersonSummary rater, PersonSummary ratee,
                             boolean hidden, Instant hiddenAt, Admin hiddenBy, String hiddenReason) {
    }

    public record RatingSummary(String userId, Double ratingAvg, long ratingCount, long hiddenCount, Map<String, Long> distribution) {
    }

    public record RatingActionResult(String action, String auditId, RatingItem rating, RatingSummary rateeSummary) {
    }
}

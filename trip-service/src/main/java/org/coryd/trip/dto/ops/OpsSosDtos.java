package org.coryd.trip.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.SosEscalationTarget;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosOutcome;
import org.coryd.common.enums.SosStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The SOS queue as trip-service serves it to ops-service (internal only). Requests carry the admin ops-service signed them with. */
public final class OpsSosDtos {

    private OpsSosDtos() {
    }

    public record Filter(SosStatus status, SosKind kind, LocalDate from, LocalDate to, String routePostId, String riderId,
                         String driverId) {
    }

    public record Row(String id, SosKind kind, SosStatus status, Instant triggeredAt, String triggeredBy, String triggeredByRole,
                      String bookingId, String riderId, String routePostId, String driverId, String vehicleId,
                      String originLabel, String destinationLabel, RouteStatus routeStatus, Instant departureTime,
                      Double latitude, Double longitude, Instant opsNotifiedAt, String safetyAlertId, String safetyCheckKind,
                      Instant acknowledgedAt, String acknowledgedBy, String acknowledgedByName,
                      Instant escalatedAt, SosEscalationTarget escalatedTo, String escalationContact,
                      Instant escalationContactedAt, String escalationReference,
                      Instant resolvedAt, String resolvedBy, String resolvedByName, SosOutcome outcome, String resolutionNote,
                      int realertsSent, Long minutesOpen) {
    }

    public record Counts(long all, long open, long acknowledged, long escalated, long resolved) {
    }

    public record Contact(String name, String phone) {
    }

    public record SafetyCheck(String alertId, String kind, String status, Instant raisedAt, Instant escalatedAt,
                              Double latitude, Double longitude, List<Answer> answers) {
    }

    public record Answer(String userId, String role, String answer) {
    }

    public record Realert(int level, int minutesOpen, Instant at) {
    }

    public record Allowed(boolean acknowledge, boolean note, boolean escalate, boolean resolve) {
    }

    public record Detail(Row incident, List<Contact> contactsNotified, SafetyCheck safetyCheck, List<SafetyCheck> tripSafetyChecks,
                         List<Realert> realerts, List<OpsAuditEntry> actions, Allowed allowed) {
    }

    /** What an action did - the incident as it now stands and the audit row. */
    public record ActionResult(String action, String auditId, SosStatus statusBefore, SosStatus statusAfter, Row incident) {
    }

    public record AcknowledgeRequest(@Size(max = 1000) String note,
                                     @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record NoteRequest(@NotBlank(message = "is required") @Size(max = 1000) String note,
                              @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record EscalateRequest(@NotNull(message = "is required - who was called") SosEscalationTarget target,
                                  @Size(max = 200) String contact, Instant contactedAt, @Size(max = 100) String reference,
                                  @Size(max = 1000) String note,
                                  @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }

    public record ResolveRequest(@NotNull(message = "is required") SosOutcome outcome,
                                 @NotBlank(message = "is required") @Size(max = 1000) String note,
                                 @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }
}

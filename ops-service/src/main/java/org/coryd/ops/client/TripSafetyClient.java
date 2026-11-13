package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Safety & support in trip-service, under /internal/ops/sos, /internal/ops/reports and /internal/ops/ratings - the SOS
 * queue, reports & disputes, and ratings moderation. A refusal (400 with trip-service's message) comes back as is.
 */
@Component
public class TripSafetyClient {

    private static final String SERVICE = "trip-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TripSafetyClient(@Value("${services.trip.url}") String tripServiceUrl,
                            @Value("${services.internal.api-key}") String internalApiKey,
                            JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(tripServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public <T> T get(Class<T> type, String path, Map<String, ?> params, Object... uriVariables) {
        return DownstreamCalls.data(SERVICE, objectMapper, type, () -> restClient.get()
                .uri(b -> {
                    b.path(path);
                    params.forEach((name, value) -> {
                        if (value instanceof Collection<?> many) {
                            many.forEach(v -> b.queryParam(name, v));
                        } else if (value != null && !(value instanceof String s && s.isBlank())) {
                            b.queryParam(name, value instanceof String s ? s.trim() : value);
                        }
                    });
                    return b.build(uriVariables);
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public <T> T post(Class<T> type, String path, Object body, Object... uriVariables) {
        return DownstreamCalls.data(SERVICE, objectMapper, type, () -> restClient.post()
                .uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    // ---- SOS queue ---------------------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosRow(String id, SosKind kind, SosStatus status, Instant triggeredAt, String triggeredBy, String triggeredByRole,
                         String bookingId, String riderId, String routePostId, String driverId, String vehicleId,
                         String originLabel, String destinationLabel, RouteStatus routeStatus, Instant departureTime,
                         Double latitude, Double longitude, Instant opsNotifiedAt, String safetyAlertId, String safetyCheckKind,
                         Instant acknowledgedAt, String acknowledgedBy, String acknowledgedByName,
                         Instant escalatedAt, SosEscalationTarget escalatedTo, String escalationContact,
                         Instant escalationContactedAt, String escalationReference,
                         Instant resolvedAt, String resolvedBy, String resolvedByName, SosOutcome outcome, String resolutionNote,
                         int realertsSent, Long minutesOpen) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosPage(List<SosRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosCounts(long all, long open, long acknowledged, long escalated, long resolved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String name, String phone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SafetyCheck(String alertId, String kind, String status, Instant raisedAt, Instant escalatedAt, Double latitude,
                              Double longitude, List<Answer> answers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Answer(String userId, String role, String answer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Realert(int level, int minutesOpen, Instant at) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Audit(String id, Instant at, String actorId, String actorName, String action, String note, String before, String after) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosAllowed(boolean acknowledge, boolean note, boolean escalate, boolean resolve) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosDetail(SosRow incident, List<Contact> contactsNotified, SafetyCheck safetyCheck, List<SafetyCheck> tripSafetyChecks,
                            List<Realert> realerts, List<Audit> actions, SosAllowed allowed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosActionResult(String action, String auditId, SosStatus statusBefore, SosStatus statusAfter, SosRow incident) {
    }

    // ---- reports -----------------------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportRow(String id, String referenceNumber, IssueReportStatus status, IssueCategory category, String description,
                            String reporterId, String reporterRole, String bookingId, String riderId, String routePostId, String driverId,
                            String originLabel, String destinationLabel, Instant departureTime, Instant createdAt,
                            String assignedTo, String assignedToName, Instant assignedAt,
                            IssueReportOutcome outcome, String messageToReporter, IssueReportActionType actionType, String actionReference,
                            Instant resolvedAt, String resolvedBy, String resolvedByName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportPage(List<ReportRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportCounts(long all, long open, long inProgress, long resolved, long dismissed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportBooking(String bookingId, BookingStatus status, Integer seatCount, BigDecimal fareTotal, String pickupLabel,
                                String dropoffLabel, Instant confirmedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportAllowed(boolean assign, boolean note, boolean resolve, boolean dismiss) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportDetail(ReportRow report, ReportBooking booking, List<String> attachments, List<ReportRow> priorReports,
                               List<Audit> actions, ReportAllowed allowed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportActionResult(String action, String auditId, IssueReportStatus statusBefore, IssueReportStatus statusAfter,
                                     ReportRow report) {
    }

    // ---- ratings -----------------------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingRow(String id, String bookingId, String routePostId, String raterId, String rateeId, RatingDirection direction,
                            int stars, String comment, Instant createdAt, boolean hidden, Instant hiddenAt, String hiddenBy,
                            String hiddenByName, String hiddenReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingPage(List<RatingRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingSummary(String userId, Double ratingAvg, long ratingCount, long hiddenCount, Map<String, Long> distribution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingActionResult(String action, String auditId, RatingRow rating, RatingSummary rateeSummary) {
    }
}

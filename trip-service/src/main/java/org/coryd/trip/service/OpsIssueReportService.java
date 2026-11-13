package org.coryd.trip.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IssueReportUpdatedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.ops.OpsReportDtos;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reports & disputes for the Ops Console. OPEN -> IN_PROGRESS (assigned to an admin) -> RESOLVED with an outcome and a
 * message the reporter is sent, or DISMISSED. A refund or a suspension is done through its own ops endpoint first and only
 * referenced here (actionType + actionReference) - never repeated. Every action is audited in the same transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsIssueReportService {

    static final int PRIOR_REPORTS = 20;

    private final IssueReportRepository issueReportRepository;
    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;
    private final OpsAuditWriter auditWriter;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public PagedResponse<OpsReportDtos.Row> list(OpsReportDtos.Filter filter, Pageable pageable) {
        Page<IssueReport> page = issueReportRepository.findAll(spec(filter),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        Context context = context(page.getContent());
        return PagedResponse.from(page.map(r -> row(r, context)));
    }

    @Transactional(readOnly = true)
    public OpsReportDtos.Counts counts(OpsReportDtos.Filter filter) {
        Map<IssueReportStatus, Long> byStatus = new LinkedHashMap<>();
        for (IssueReportStatus status : IssueReportStatus.values()) {
            byStatus.put(status, issueReportRepository.count(spec(new OpsReportDtos.Filter(status, filter.category(), filter.reporterRole(),
                    filter.from(), filter.to(), filter.routePostId(), filter.assignedTo(), filter.userId(), filter.search()))));
        }
        return new OpsReportDtos.Counts(byStatus.values().stream().mapToLong(Long::longValue).sum(), byStatus.get(IssueReportStatus.OPEN),
                byStatus.get(IssueReportStatus.IN_PROGRESS), byStatus.get(IssueReportStatus.RESOLVED), byStatus.get(IssueReportStatus.DISMISSED));
    }

    @Transactional(readOnly = true)
    public OpsReportDtos.Detail detail(String reportId) {
        IssueReport report = issueReportRepository.findById(reportId).orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        Context context = context(List.of(report));
        OpsReportDtos.Row row = row(report, context);
        Booking booking = report.getBookingId() == null ? null : context.bookings.get(report.getBookingId());

        Set<String> people = new LinkedHashSet<>();
        people.add(report.getReportedBy());
        if (row.riderId() != null) {
            people.add(row.riderId());
        }
        if (row.driverId() != null) {
            people.add(row.driverId());
        }
        List<IssueReport> prior = issueReportRepository.involving(people, report.getId(), PRIOR_REPORTS);
        Context priorContext = context(prior);
        return new OpsReportDtos.Detail(row,
                booking == null ? null : new OpsReportDtos.Booking(booking.getId(), booking.getStatus(), booking.getSeatCount(),
                        booking.getFareTotal(), booking.getPickupLabel(), booking.getDropoffLabel(), booking.getConfirmedAt()),
                List.of(), prior.stream().map(p -> row(p, priorContext)).toList(),
                auditWriter.history(OpsAuditLog.TargetType.ISSUE, report.getId()), allowed(status(report)));
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    /** To the acting admin unless assigneeId says otherwise; reassigning is allowed. Moves an OPEN report to IN_PROGRESS. */
    @Transactional
    public OpsReportDtos.ActionResult assign(String reportId, OpsReportDtos.AssignRequest request) {
        IssueReport report = lock(reportId);
        IssueReportStatus before = requireOpen(report, "assigned");
        String assignee = request.assigneeId() == null || request.assigneeId().isBlank() ? request.actorId() : request.assigneeId().trim();
        String assigneeName = assignee.equals(request.actorId()) ? OpsSosService.name(request.actorId(), request.actorName())
                : request.assigneeName() == null || request.assigneeName().isBlank() ? assignee : request.assigneeName().trim();
        Map<String, Object> beforeState = state(report);
        report.setAssignedTo(assignee);
        report.setAssignedToName(assigneeName);
        report.setAssignedAt(Instant.now());
        report.setStatus(IssueReportStatus.IN_PROGRESS.name());
        String note = "Assigned to " + assigneeName + (request.note() == null || request.note().isBlank() ? "" : " - " + request.note().trim());
        return act(report, before, beforeState, OpsAuditLog.Action.REPORT_ASSIGNED, request.actorId(), request.actorName(), note);
    }

    /** Internal - the reporter never sees it. Any status. */
    @Transactional
    public OpsReportDtos.ActionResult note(String reportId, OpsReportDtos.NoteRequest request) {
        String note = require(request.note(), "note");
        IssueReport report = lock(reportId);
        return act(report, status(report), state(report), OpsAuditLog.Action.REPORT_NOTE, request.actorId(), request.actorName(), note);
    }

    @Transactional
    public OpsReportDtos.ActionResult resolve(String reportId, OpsReportDtos.ResolveRequest request) {
        if (request.outcome() == null) {
            throw new BadRequestException("outcome: is required - NO_ACTION, WARNING, SUSPENDED, REFUND_ISSUED or OTHER");
        }
        String note = require(request.note(), "note");
        String message = require(request.messageToReporter(), "messageToReporter");
        String reference = request.actionReference() == null || request.actionReference().isBlank() ? null : request.actionReference().trim();
        if (reference != null && request.actionType() == null) {
            throw new BadRequestException("actionType: is required with an actionReference - BOOKING_CANCELLATION, REFUND, SUSPENSION or OTHER");
        }
        IssueReport report = lock(reportId);
        IssueReportStatus before = requireOpen(report, "resolved");
        Map<String, Object> beforeState = state(report);
        if (report.getAssignedTo() == null) {
            report.setAssignedTo(request.actorId());
            report.setAssignedToName(OpsSosService.name(request.actorId(), request.actorName()));
            report.setAssignedAt(Instant.now());
        }
        report.setStatus(IssueReportStatus.RESOLVED.name());
        report.setOutcome(request.outcome());
        report.setMessageToReporter(message);
        report.setActionType(request.actionType());
        report.setActionReference(reference);
        finish(report, request.actorId(), request.actorName());
        OpsReportDtos.ActionResult result = act(report, before, beforeState, OpsAuditLog.Action.REPORT_RESOLVED, request.actorId(),
                request.actorName(), note);
        tellReporter(report, message);
        return result;
    }

    /** Not a real problem (a duplicate, a test, nothing to act on). A message to the reporter is optional here. */
    @Transactional
    public OpsReportDtos.ActionResult dismiss(String reportId, OpsReportDtos.DismissRequest request) {
        String note = require(request.note(), "note");
        IssueReport report = lock(reportId);
        IssueReportStatus before = requireOpen(report, "dismissed");
        Map<String, Object> beforeState = state(report);
        report.setStatus(IssueReportStatus.DISMISSED.name());
        String message = request.messageToReporter() == null || request.messageToReporter().isBlank() ? null : request.messageToReporter().trim();
        report.setMessageToReporter(message);
        finish(report, request.actorId(), request.actorName());
        OpsReportDtos.ActionResult result = act(report, before, beforeState, OpsAuditLog.Action.REPORT_DISMISSED, request.actorId(),
                request.actorName(), note);
        if (message != null) {
            tellReporter(report, message);
        }
        return result;
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private IssueReport lock(String reportId) {
        return issueReportRepository.lockById(reportId).orElseThrow(() -> new ResourceNotFoundException("Report not found"));
    }

    private static IssueReportStatus requireOpen(IssueReport report, String doing) {
        IssueReportStatus status = status(report);
        if (status == IssueReportStatus.RESOLVED || status == IssueReportStatus.DISMISSED) {
            throw new BadRequestException("This report is already " + status.name().toLowerCase(Locale.ROOT) + " - it can't be " + doing);
        }
        return status;
    }

    private static void finish(IssueReport report, String actorId, String actorName) {
        report.setResolvedAt(Instant.now());
        report.setResolvedBy(actorId);
        report.setResolvedByName(OpsSosService.name(actorId, actorName));
    }

    private OpsReportDtos.ActionResult act(IssueReport report, IssueReportStatus before, Map<String, Object> beforeState,
                                           OpsAuditLog.Action action, String actorId, String actorName, String note) {
        issueReportRepository.save(report);
        Context context = context(List.of(report));
        OpsAuditLog audit = auditWriter.write(actorId, actorName, action, OpsAuditLog.TargetType.ISSUE, report.getId(),
                context.routeIdOf(report), beforeState, state(report), note);
        eventPublisher.publishEvent(new OpsCountsPublisher.Dirty());
        log.info("OPS {} {} report {} ({} -> {})", actorId, action, report.getReferenceNumber(), before, report.getStatus());
        return new OpsReportDtos.ActionResult(action.name(), audit.getId(), before, status(report), row(report, context));
    }

    private void tellReporter(IssueReport report, String message) {
        IssueReportUpdatedEvent event = IssueReportUpdatedEvent.builder()
                .issueId(report.getId()).referenceNumber(report.getReferenceNumber()).reporterId(report.getReportedBy())
                .bookingId(report.getBookingId()).status(report.getStatus())
                .outcome(report.getOutcome() == null ? null : report.getOutcome().name()).message(message)
                .build();
        OpsAuditWriter.afterCommit(() -> {
            try {
                kafkaTemplate.send(KafkaTopics.ISSUE_REPORT_UPDATED, report.getReportedBy(), event);
            } catch (Exception e) {
                log.warn("Could not tell the reporter of {} about its outcome: {}", report.getReferenceNumber(), e.getMessage());
            }
        });
    }

    private static Map<String, Object> state(IssueReport report) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", report.getStatus());
        state.put("assignedTo", report.getAssignedTo());
        if (report.getOutcome() != null) {
            state.put("outcome", report.getOutcome());
        }
        if (report.getActionType() != null) {
            state.put("actionType", report.getActionType());
            state.put("actionReference", report.getActionReference());
        }
        return state;
    }

    private static OpsReportDtos.Allowed allowed(IssueReportStatus status) {
        boolean live = status == IssueReportStatus.OPEN || status == IssueReportStatus.IN_PROGRESS;
        return new OpsReportDtos.Allowed(live, true, live, live);
    }

    static IssueReportStatus status(IssueReport report) {
        try {
            return "IN_REVIEW".equals(report.getStatus()) ? IssueReportStatus.IN_PROGRESS : IssueReportStatus.valueOf(report.getStatus());
        } catch (Exception e) {
            return IssueReportStatus.OPEN;
        }
    }

    private record Context(Map<String, Booking> bookings, Map<String, RoutePost> routes) {
        String routeIdOf(IssueReport report) {
            Booking b = report.getBookingId() == null ? null : bookings.get(report.getBookingId());
            return b == null ? null : b.getRoutePostId();
        }
    }

    private Context context(List<IssueReport> reports) {
        List<String> bookingIds = reports.stream().map(IssueReport::getBookingId).filter(Objects::nonNull).distinct().toList();
        Map<String, Booking> bookings = bookingIds.isEmpty() ? Map.of() : bookingRepository.findAllById(bookingIds).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<String> routeIds = bookings.values().stream().map(Booking::getRoutePostId).distinct().toList();
        Map<String, RoutePost> routes = routeIds.isEmpty() ? Map.of() : routePostRepository.findAllById(routeIds).stream()
                .collect(Collectors.toMap(RoutePost::getId, Function.identity()));
        return new Context(bookings, routes);
    }

    private static OpsReportDtos.Row row(IssueReport r, Context context) {
        Booking booking = r.getBookingId() == null ? null : context.bookings.get(r.getBookingId());
        RoutePost route = booking == null ? null : context.routes.get(booking.getRoutePostId());
        return new OpsReportDtos.Row(r.getId(), r.getReferenceNumber(), status(r), r.getCategory(), r.getDescription(), r.getReportedBy(),
                r.getReporterRole(), r.getBookingId(), booking == null ? null : booking.getRiderId(),
                booking == null ? null : booking.getRoutePostId(), route == null ? null : route.getDriverId(),
                route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(),
                route == null ? null : route.getDepartureTime(), r.getCreatedAt(), r.getAssignedTo(), r.getAssignedToName(),
                r.getAssignedAt(), r.getOutcome(), r.getMessageToReporter(), r.getActionType(), r.getActionReference(),
                r.getResolvedAt(), r.getResolvedBy(), r.getResolvedByName());
    }

    private static Specification<IssueReport> spec(OpsReportDtos.Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter.status() != null) {
                where.add(filter.status() == IssueReportStatus.IN_PROGRESS
                        ? root.get("status").in(IssueReportStatus.IN_PROGRESS.name(), "IN_REVIEW")
                        : cb.equal(root.get("status"), filter.status().name()));
            }
            if (filter.category() != null) {
                where.add(cb.equal(root.get("category"), filter.category()));
            }
            if (filter.reporterRole() != null && !filter.reporterRole().isBlank()) {
                where.add(cb.equal(root.get("reporterRole"), filter.reporterRole().trim().toUpperCase(Locale.ROOT)));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), OpsSosService.startOfLagosDay(filter.from())));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), OpsSosService.startOfLagosDay(filter.to().plusDays(1))));
            }
            if (filter.assignedTo() != null && !filter.assignedTo().isBlank()) {
                where.add(cb.equal(root.get("assignedTo"), filter.assignedTo().trim()));
            }
            if (filter.routePostId() != null && !filter.routePostId().isBlank()) {
                Subquery<String> onTrip = query.subquery(String.class);
                var booking = onTrip.from(Booking.class);
                onTrip.select(booking.get("id")).where(cb.equal(booking.get("id"), root.get("bookingId")),
                        cb.equal(booking.get("routePostId"), filter.routePostId().trim()));
                where.add(cb.exists(onTrip));
            }
            if (filter.userId() != null && !filter.userId().isBlank()) {
                String user = filter.userId().trim();
                Subquery<String> involved = query.subquery(String.class);
                var booking = involved.from(Booking.class);
                Subquery<String> driven = involved.subquery(String.class);
                var route = driven.from(RoutePost.class);
                driven.select(route.get("id")).where(cb.equal(route.get("id"), booking.get("routePostId")), cb.equal(route.get("driverId"), user));
                involved.select(booking.get("id")).where(cb.equal(booking.get("id"), root.get("bookingId")),
                        cb.or(cb.equal(booking.get("riderId"), user), cb.exists(driven)));
                where.add(cb.or(cb.equal(root.get("reportedBy"), user), cb.exists(involved)));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                String term = filter.search().trim().toLowerCase(Locale.ROOT);
                String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("referenceNumber")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + ": is required");
        }
        return value.trim();
    }
}

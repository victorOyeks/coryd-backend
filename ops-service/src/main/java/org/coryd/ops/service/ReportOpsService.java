package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportActionType;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.TripSafetyClient;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ops Console - Reports & disputes. trip-service owns the reports and the workflow; this adds who everyone is. A refund or
 * a suspension is never done from here: the console calls the existing endpoint (relatedActions lists them), then resolves
 * the report with what it returned as actionReference.
 */
@Service
@RequiredArgsConstructor
public class ReportOpsService {

    private final TripSafetyClient tripSafetyClient;
    private final OpsPeople opsPeople;

    public record Query(IssueReportStatus status, IssueCategory category, String reporterRole, LocalDate from, LocalDate to,
                        String routePostId, String assignedTo, String userId, String search) {
        Map<String, Object> params() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("status", status);
            p.put("category", category);
            p.put("reporterRole", reporterRole);
            p.put("from", from);
            p.put("to", to);
            p.put("routePostId", routePostId);
            p.put("assignedTo", assignedTo);
            p.put("userId", userId);
            p.put("search", search);
            return p;
        }
    }

    public PagedResponse<SafetyViews.ReportItem> list(Query query, Pageable pageable) {
        Map<String, Object> params = query.params();
        params.put("page", pageable.getPageNumber());
        params.put("size", pageable.getPageSize());
        TripSafetyClient.ReportPage page = tripSafetyClient.get(TripSafetyClient.ReportPage.class, "/internal/ops/reports", params);
        OpsPeople.Lookup people = people(page.content());
        return new PagedResponse<>(page.content().stream().map(r -> item(r, people)).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages(), page.last());
    }

    public SafetyViews.ReportCounts counts(Query query) {
        Map<String, Object> params = query.params();
        params.remove("status");
        TripSafetyClient.ReportCounts c = tripSafetyClient.get(TripSafetyClient.ReportCounts.class, "/internal/ops/reports/counts", params);
        return new SafetyViews.ReportCounts(c.all(), c.open(), c.inProgress(), c.resolved(), c.dismissed());
    }

    public SafetyViews.ReportDetail detail(String reportId) {
        TripSafetyClient.ReportDetail d = tripSafetyClient.get(TripSafetyClient.ReportDetail.class, "/internal/ops/reports/{id}", Map.of(), reportId);
        List<TripSafetyClient.ReportRow> all = new ArrayList<>();
        all.add(d.report());
        all.addAll(d.priorReports());
        OpsPeople.Lookup people = people(all);
        TripSafetyClient.ReportRow r = d.report();
        List<String> parties = new ArrayList<>();
        parties.add(r.reporterId());
        if (r.riderId() != null && !parties.contains(r.riderId())) {
            parties.add(r.riderId());
        }
        if (r.driverId() != null && !parties.contains(r.driverId())) {
            parties.add(r.driverId());
        }
        Map<String, Long> priorCounts = new LinkedHashMap<>();
        parties.forEach(p -> priorCounts.put(p, 0L));
        List<SafetyViews.PriorReport> prior = d.priorReports().stream().map(p -> {
            List<String> involves = parties.stream()
                    .filter(id -> id.equals(p.reporterId()) || id.equals(p.riderId()) || id.equals(p.driverId())).toList();
            involves.forEach(id -> priorCounts.merge(id, 1L, Long::sum));
            return new SafetyViews.PriorReport(item(p, people), involves);
        }).toList();
        TripSafetyClient.ReportBooking b = d.booking();
        return new SafetyViews.ReportDetail(item(r, people),
                b == null ? null : new SafetyViews.ReportBooking(b.bookingId(), TripOpsService.ref("B", b.bookingId()), b.status(),
                        b.seatCount(), b.fareTotal(), b.pickupLabel(), b.dropoffLabel(), b.confirmedAt()),
                d.attachments() == null ? List.of() : d.attachments(), prior, priorCounts, SosOpsService.audit(d.actions()),
                relatedActions(r),
                new SafetyViews.ReportAllowed(d.allowed().assign(), d.allowed().note(), d.allowed().resolve(), d.allowed().dismiss()));
    }

    /** The existing ops endpoints a report can lead to - never repeated here, only referenced afterwards. */
    static List<SafetyViews.RelatedAction> relatedActions(TripSafetyClient.ReportRow r) {
        List<SafetyViews.RelatedAction> actions = new ArrayList<>();
        if (r.bookingId() != null) {
            actions.add(new SafetyViews.RelatedAction("CANCEL_BOOKING_WITH_REFUND", "POST", "/bookings/" + r.bookingId() + "/cancel",
                    IssueReportActionType.BOOKING_CANCELLATION, "the auditId it returns"));
        }
        if (r.driverId() != null) {
            actions.add(new SafetyViews.RelatedAction("SUSPEND_DRIVER", "POST", "/users/" + r.driverId() + "/suspend",
                    IssueReportActionType.SUSPENSION, r.driverId()));
        }
        if (r.riderId() != null) {
            actions.add(new SafetyViews.RelatedAction("SUSPEND_RIDER", "POST", "/users/" + r.riderId() + "/suspend",
                    IssueReportActionType.SUSPENSION, r.riderId()));
        }
        return actions;
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    public SafetyViews.ReportActionResult assign(String reportId, SafetyRequests.ReportAssign request, CoRydUserDetails admin) {
        Map<String, Object> body = SosOpsService.signed(admin);
        if (request != null) {
            body.put("assigneeId", SosOpsService.trim(request.assigneeId()));
            body.put("assigneeName", SosOpsService.trim(request.assigneeName()));
            body.put("note", SosOpsService.trim(request.note()));
        }
        return action(reportId, "assign", body);
    }

    public SafetyViews.ReportActionResult note(String reportId, SafetyRequests.ReportNote request, CoRydUserDetails admin) {
        Map<String, Object> body = SosOpsService.signed(admin);
        body.put("note", request.note().trim());
        return action(reportId, "notes", body);
    }

    public SafetyViews.ReportActionResult resolve(String reportId, SafetyRequests.ReportResolve request, CoRydUserDetails admin) {
        Map<String, Object> body = SosOpsService.signed(admin);
        body.put("outcome", request.outcome());
        body.put("note", request.note().trim());
        body.put("messageToReporter", request.messageToReporter().trim());
        body.put("actionType", request.actionType());
        body.put("actionReference", SosOpsService.trim(request.actionReference()));
        return action(reportId, "resolve", body);
    }

    public SafetyViews.ReportActionResult dismiss(String reportId, SafetyRequests.ReportDismiss request, CoRydUserDetails admin) {
        Map<String, Object> body = SosOpsService.signed(admin);
        body.put("note", request.note().trim());
        body.put("messageToReporter", SosOpsService.trim(request.messageToReporter()));
        return action(reportId, "dismiss", body);
    }

    private SafetyViews.ReportActionResult action(String reportId, String verb, Map<String, Object> body) {
        TripSafetyClient.ReportActionResult r = tripSafetyClient.post(TripSafetyClient.ReportActionResult.class,
                "/internal/ops/reports/{id}/" + verb, body, reportId);
        return new SafetyViews.ReportActionResult(r.action(), r.auditId(), r.statusBefore(), r.statusAfter(),
                item(r.report(), people(List.of(r.report()))));
    }

    // ---- mapping -------------------------------------------------------------------------------------------------

    private OpsPeople.Lookup people(List<TripSafetyClient.ReportRow> rows) {
        List<String> drivers = new ArrayList<>();
        List<String> riders = new ArrayList<>();
        for (TripSafetyClient.ReportRow r : rows) {
            drivers.add(r.driverId());
            riders.add(r.riderId());
            if ("DRIVER".equals(r.reporterRole())) {
                drivers.add(r.reporterId());
            } else {
                riders.add(r.reporterId());
            }
        }
        return opsPeople.lookup(drivers, riders, List.of());
    }

    static SafetyViews.ReportItem item(TripSafetyClient.ReportRow r, OpsPeople.Lookup people) {
        return new SafetyViews.ReportItem(r.id(), r.referenceNumber(), r.status(), r.category(), r.description(),
                people.person(r.reporterId(), r.reporterRole() == null ? "RIDER" : r.reporterRole()),
                r.bookingId(), TripOpsService.ref("B", r.bookingId()), r.routePostId(), TripOpsService.ref("T", r.routePostId()),
                r.originLabel(), r.destinationLabel(), r.departureTime(), people.driver(r.driverId()), people.rider(r.riderId()),
                r.createdAt(), SosOpsService.admin(r.assignedTo(), r.assignedToName()), r.assignedAt(), r.outcome(), r.messageToReporter(),
                r.actionType(), r.actionReference(), r.resolvedAt(), SosOpsService.admin(r.resolvedBy(), r.resolvedByName()));
    }
}

package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.NotificationServiceClient;
import org.coryd.ops.client.TripSafetyClient;
import org.coryd.ops.dto.RiderSummary;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ops Console - the SOS queue: SOS someone pressed and "Are you OK?" checks nobody answered, worked as one queue. trip-service
 * owns the incidents and every action; names and phones come from profile- and identity-service (one batch call each per
 * page), the trip's live location and passengers from the trip detail, and which channel each alert went out on from
 * notification-service's delivery records. Every action is signed with the admin's id and name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SosOpsService {

    static final List<String> CONTACT_EVENTS = List.of("SOS_TRIGGERED");
    static final List<String> OPS_EVENTS = List.of("SOS_OPS_ALERT", "SAFETY_ESCALATED", "SOS_OPS_REALERT");
    /** Delivery rows carry no incident id: an SOS's are the ones of its kinds within this window of it being raised. */
    static final Duration DELIVERY_WINDOW = Duration.ofMinutes(15);

    private final TripSafetyClient tripSafetyClient;
    private final TripOpsService tripOpsService;
    private final OpsPeople opsPeople;
    private final NotificationServiceClient notificationServiceClient;

    public record Query(SosStatus status, SosKind kind, LocalDate from, LocalDate to, String routePostId, String riderId, String driverId) {
        Map<String, Object> params() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("status", status);
            p.put("kind", kind);
            p.put("from", from);
            p.put("to", to);
            p.put("routePostId", routePostId);
            p.put("riderId", riderId);
            p.put("driverId", driverId);
            return p;
        }
    }

    public PagedResponse<SafetyViews.SosItem> list(Query query, Pageable pageable) {
        Map<String, Object> params = query.params();
        params.put("page", pageable.getPageNumber());
        params.put("size", pageable.getPageSize());
        TripSafetyClient.SosPage page = tripSafetyClient.get(TripSafetyClient.SosPage.class, "/internal/ops/sos/queue", params);
        OpsPeople.Lookup people = people(page.content());
        return new PagedResponse<>(page.content().stream().map(r -> item(r, people)).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages(), page.last());
    }

    public SafetyViews.SosCounts counts(Query query) {
        Map<String, Object> params = query.params();
        params.remove("status");
        TripSafetyClient.SosCounts c = tripSafetyClient.get(TripSafetyClient.SosCounts.class, "/internal/ops/sos/counts", params);
        return new SafetyViews.SosCounts(c.all(), c.open(), c.acknowledged(), c.escalated(), c.resolved());
    }

    public SafetyViews.SosDetail detail(String incidentId) {
        TripSafetyClient.SosDetail d = tripSafetyClient.get(TripSafetyClient.SosDetail.class, "/internal/ops/sos/{id}", Map.of(), incidentId);
        TripSafetyClient.SosRow row = d.incident();
        TripDetailResponse trip = null;
        if (row.routePostId() != null) {
            try {
                trip = tripOpsService.trip(row.routePostId());
            } catch (Exception e) {
                log.warn("SOS {}: trip {} unavailable for the detail: {}", incidentId, row.routePostId(), e.getMessage());
            }
        }
        List<String> answerers = Stream.concat(Stream.ofNullable(d.safetyCheck()), d.tripSafetyChecks().stream())
                .flatMap(c -> c.answers().stream()).map(TripSafetyClient.Answer::userId).distinct().toList();
        OpsPeople.Lookup people = people(List.of(row), answerers);

        List<NotificationServiceClient.Delivery> deliveries = row.triggeredAt() == null ? null : notificationServiceClient.deliveries(
                Stream.concat(CONTACT_EVENTS.stream(), OPS_EVENTS.stream()).toList(),
                row.triggeredAt().minus(Duration.ofMinutes(1)), row.triggeredAt().plus(windowFor(row)));
        List<SafetyViews.ContactNotified> contacts = d.contactsNotified().stream().map(c -> new SafetyViews.ContactNotified(c.name(), c.phone(),
                deliveries == null ? null : deliveries.stream()
                        .filter(x -> CONTACT_EVENTS.contains(x.eventType()) && samePhone(c.phone(), x.recipientPhone()))
                        .map(x -> new SafetyViews.Delivery(x.at(), x.channel(), x.delivered(), x.errorCode())).toList())).toList();
        String firstAlert = row.kind() == SosKind.SAFETY_CHECK ? "SAFETY_ESCALATED" : "SOS_OPS_ALERT";
        List<SafetyViews.OpsAlert> opsAlerts = deliveries == null ? null : deliveries.stream()
                .filter(x -> x.eventType().equals(firstAlert) || x.eventType().equals("SOS_OPS_REALERT"))
                .map(x -> new SafetyViews.OpsAlert(x.at(), x.eventType(), x.channel(),
                        x.recipientPhone() != null ? x.recipientPhone() : x.recipientEmail(), x.delivered(), x.errorCode()))
                .toList();

        SafetyViews.SafetyCheck linked = d.safetyCheck() == null ? null : safetyCheck(d.safetyCheck(), people);
        List<SafetyViews.SafetyCheck> checks = d.tripSafetyChecks().stream().map(c -> safetyCheck(c, people)).toList();
        List<RiderSummary> riders = trip == null ? List.of() : trip.passengers().stream()
                .filter(p -> p.status() != null && List.of("CONFIRMED", "EN_ROUTE_TO_PICKUP", "AT_PICKUP", "IN_PROGRESS").contains(p.status().name()))
                .map(p -> new RiderSummary(p.riderId(), TripOpsService.ref("R", p.riderId()), p.name(), p.phone(), p.photoUrl()))
                .toList();
        SafetyViews.Location location = trip == null ? null : new SafetyViews.Location(trip.location(), trip.lastSeen(), trip.locationAgeSeconds());
        SafetyViews.SosItem item = item(row, people);
        return new SafetyViews.SosDetail(item, location, riders, contacts, opsAlerts, linked, checks,
                timeline(item, d, checks), audit(d.actions()),
                new SafetyViews.SosAllowed(d.allowed().acknowledge(), d.allowed().note(), d.allowed().escalate(), d.allowed().resolve()),
                trip);
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    public SafetyViews.SosActionResult acknowledge(String incidentId, SafetyRequests.SosAcknowledge request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin);
        body.put("note", request == null ? null : trim(request.note()));
        return action(incidentId, "acknowledge", body);
    }

    public SafetyViews.SosActionResult note(String incidentId, SafetyRequests.SosNote request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin);
        body.put("note", request.note().trim());
        return action(incidentId, "notes", body);
    }

    public SafetyViews.SosActionResult escalate(String incidentId, SafetyRequests.SosEscalate request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin);
        body.put("target", request.target());
        body.put("contact", trim(request.contact()));
        body.put("contactedAt", request.contactedAt());
        body.put("reference", trim(request.reference()));
        body.put("note", trim(request.note()));
        return action(incidentId, "escalate", body);
    }

    public SafetyViews.SosActionResult resolve(String incidentId, SafetyRequests.SosResolve request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin);
        body.put("outcome", request.outcome());
        body.put("note", request.note().trim());
        return action(incidentId, "resolve", body);
    }

    private SafetyViews.SosActionResult action(String incidentId, String verb, Map<String, Object> body) {
        TripSafetyClient.SosActionResult r = tripSafetyClient.post(TripSafetyClient.SosActionResult.class,
                "/internal/ops/sos/{id}/" + verb, body, incidentId);
        return new SafetyViews.SosActionResult(r.action(), r.auditId(), r.statusBefore(), r.statusAfter(),
                item(r.incident(), people(List.of(r.incident()))));
    }

    // ---- mapping -------------------------------------------------------------------------------------------------

    private OpsPeople.Lookup people(List<TripSafetyClient.SosRow> rows) {
        return people(rows, List.of());
    }

    private OpsPeople.Lookup people(List<TripSafetyClient.SosRow> rows, List<String> alsoRiders) {
        List<String> drivers = new ArrayList<>();
        List<String> riders = new ArrayList<>(alsoRiders);
        for (TripSafetyClient.SosRow r : rows) {
            drivers.add(r.driverId());
            riders.add(r.riderId());
            if ("DRIVER".equals(r.triggeredByRole())) {
                drivers.add(r.triggeredBy());
            } else if ("RIDER".equals(r.triggeredByRole())) {
                riders.add(r.triggeredBy());
            }
        }
        return opsPeople.lookup(drivers, riders, rows.stream().map(TripSafetyClient.SosRow::vehicleId).toList());
    }

    static SafetyViews.SosItem item(TripSafetyClient.SosRow r, OpsPeople.Lookup people) {
        SafetyViews.Escalation escalation = r.escalatedAt() == null ? null : new SafetyViews.Escalation(r.escalatedAt(), r.escalatedTo(),
                r.escalationContact(), r.escalationContactedAt(), r.escalationReference());
        SafetyViews.Resolution resolution = r.resolvedAt() == null ? null : new SafetyViews.Resolution(r.resolvedAt(),
                admin(r.resolvedBy(), r.resolvedByName()), r.outcome(), r.resolutionNote());
        return new SafetyViews.SosItem(r.id(), TripOpsService.ref("S", r.id()), r.kind(), r.status(), r.triggeredAt(), r.minutesOpen(),
                people.person(r.triggeredBy(), r.triggeredByRole()), r.safetyCheckKind(), r.safetyAlertId(),
                r.routePostId(), TripOpsService.ref("T", r.routePostId()), r.bookingId(), TripOpsService.ref("B", r.bookingId()),
                r.originLabel(), r.destinationLabel(), r.routeStatus(), r.departureTime(),
                people.driver(r.driverId()), people.rider(r.riderId()), people.vehicle(r.vehicleId()),
                r.latitude(), r.longitude(), r.opsNotifiedAt(), r.acknowledgedAt(), admin(r.acknowledgedBy(), r.acknowledgedByName()),
                escalation, resolution, r.realertsSent());
    }

    private static SafetyViews.SafetyCheck safetyCheck(TripSafetyClient.SafetyCheck c, OpsPeople.Lookup people) {
        List<SafetyViews.SafetyAnswer> answers = c.answers().stream().map(a -> {
            boolean driver = "DRIVER".equals(a.role());
            String name = driver ? people.driver(a.userId()).name() : people.rider(a.userId()).name();
            String phone = driver ? people.driver(a.userId()).phone() : people.rider(a.userId()).phone();
            return new SafetyViews.SafetyAnswer(a.userId(), a.role(), name, phone, a.answer());
        }).toList();
        return new SafetyViews.SafetyCheck(c.alertId(), c.kind(), c.status(), c.raisedAt(), c.escalatedAt(), c.latitude(), c.longitude(), answers);
    }

    /** The incident's own story: raised, the trip's safety checks, each re-alert, and each ops action - oldest first. */
    private static List<SafetyViews.TimelineEntry> timeline(SafetyViews.SosItem item, TripSafetyClient.SosDetail d,
                                                            List<SafetyViews.SafetyCheck> checks) {
        List<SafetyViews.TimelineEntry> entries = new ArrayList<>();
        if (item.kind() == SosKind.SAFETY_CHECK) {
            entries.add(new SafetyViews.TimelineEntry(item.raisedAt(), "SAFETY_CHECK_UNANSWERED", "SYSTEM", null, null,
                    item.safetyCheckKind() + " - nobody answered \"Are you OK?\" in time; ops alerted"));
        } else {
            entries.add(new SafetyViews.TimelineEntry(item.raisedAt(), "SOS_RAISED", item.raisedBy() == null ? "RIDER" : item.raisedBy().role(),
                    item.raisedBy() == null ? null : item.raisedBy().id(), item.raisedBy() == null ? null : item.raisedBy().name(),
                    item.latitude() == null ? "Location not shared" : "At " + item.latitude() + "," + item.longitude()));
        }
        for (SafetyViews.SafetyCheck c : checks) {
            String answered = c.answers().stream().map(a -> (a.name() == null ? a.role().toLowerCase() : a.name()) + ": " + a.answer())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            entries.add(new SafetyViews.TimelineEntry(c.raisedAt(), "SAFETY_CHECK", "SYSTEM", null, null,
                    c.kind() + " - " + c.status() + (answered.isEmpty() ? "" : " (" + answered + ")")));
            if (c.escalatedAt() != null) {
                entries.add(new SafetyViews.TimelineEntry(c.escalatedAt(), "SAFETY_CHECK_ESCALATED", "SYSTEM", null, null,
                        c.kind() + " - unanswered, ops alerted"));
            }
        }
        for (TripSafetyClient.Realert r : d.realerts()) {
            entries.add(new SafetyViews.TimelineEntry(r.at(), "REALERT", "SYSTEM", null, null,
                    "Still unacknowledged after " + r.minutesOpen() + " min - ops alerted again"));
        }
        for (TripSafetyClient.Audit a : d.actions()) {
            entries.add(new SafetyViews.TimelineEntry(a.at(), "OPS_" + a.action().replace("SOS_", ""), "OPS", a.actorId(), a.actorName(), a.note()));
        }
        entries.sort(Comparator.comparing(SafetyViews.TimelineEntry::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        return entries;
    }

    static List<SafetyViews.AuditEntry> audit(List<TripSafetyClient.Audit> actions) {
        return actions == null ? List.of() : actions.stream()
                .map(a -> new SafetyViews.AuditEntry(a.id(), a.at(), admin(a.actorId(), a.actorName()), a.action(), a.note(), a.before(), a.after()))
                .toList();
    }

    private static Duration windowFor(TripSafetyClient.SosRow row) {
        // Re-alerts can come up to the last threshold after it was raised; keep the window bounded either way.
        return row.realertsSent() > 0 ? Duration.ofHours(1) : DELIVERY_WINDOW;
    }

    static boolean samePhone(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.replaceAll("\\D", "");
        String y = b.replaceAll("\\D", "");
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        String tailX = x.length() > 10 ? x.substring(x.length() - 10) : x;
        String tailY = y.length() > 10 ? y.substring(y.length() - 10) : y;
        return tailX.equals(tailY);
    }

    static SafetyViews.Admin admin(String id, String name) {
        return id == null ? null : new SafetyViews.Admin(id, name == null ? id : name);
    }

    static Map<String, Object> signed(CoRydUserDetails admin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorId", admin.getUserId());
        String name = admin.getFullName();
        body.put("actorName", name == null || name.isBlank() ? admin.getUserId() : name);
        return body;
    }

    static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

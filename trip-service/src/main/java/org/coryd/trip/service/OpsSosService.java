package org.coryd.trip.service;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosOutcome;
import org.coryd.common.enums.SosStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsSosChangedEvent;
import org.coryd.common.kafka.events.SosOpsRealertEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.SosOpsProperties;
import org.coryd.trip.dto.ops.OpsSosDtos;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.entity.SosRealert;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.coryd.trip.repository.SosRealertRepository;
import org.coryd.trip.repository.TripSafetyAlertRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The SOS queue for the Ops Console. An SOS someone pressed and an "Are you OK?" check nobody answered are both incidents
 * here (kind SOS / SAFETY_CHECK) and are worked the same way: OPEN -> ACKNOWLEDGED (an admin claims it) -> ESCALATED (an
 * emergency service or contact was called) -> RESOLVED with an outcome. Each action takes a row lock, is audited in the
 * same transaction, and is announced on Kafka after commit for the console's live stream. An incident left OPEN past each
 * configured threshold re-alerts ops once per threshold over the same SMS / WhatsApp / email path as the first alert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsSosService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final int LEGACY_NO_REALERT = 99;
    /** An escalation time a few minutes ahead is clock skew; much more is a typo. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final SosIncidentRepository sosIncidentRepository;
    private final SosRealertRepository sosRealertRepository;
    private final TripSafetyAlertRepository tripSafetyAlertRepository;
    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final OpsAuditWriter auditWriter;
    private final SosOpsProperties properties;
    private final IdentityServiceClient identityServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // ---- queue ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<OpsSosDtos.Row> list(OpsSosDtos.Filter filter, Pageable pageable) {
        Page<SosIncident> page = sosIncidentRepository.findAll(spec(filter),
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        Map<String, RoutePost> routes = routes(page.getContent());
        Map<String, Booking> bookings = bookings(page.getContent());
        Map<String, TripSafetyAlert> alerts = alerts(page.getContent());
        Instant now = Instant.now();
        return PagedResponse.from(page.map(s -> row(s, routes.get(s.getRoutePostId()), bookings.get(s.getBookingId()),
                alerts.get(s.getSafetyAlertId()), now)));
    }

    /** Tab badges - every filter but the status. */
    @Transactional(readOnly = true)
    public OpsSosDtos.Counts counts(OpsSosDtos.Filter filter) {
        OpsSosDtos.Filter any = new OpsSosDtos.Filter(null, filter.kind(), filter.from(), filter.to(), filter.routePostId(),
                filter.riderId(), filter.driverId());
        Map<SosStatus, Long> byStatus = new LinkedHashMap<>();
        for (SosStatus status : SosStatus.values()) {
            OpsSosDtos.Filter one = new OpsSosDtos.Filter(status, any.kind(), any.from(), any.to(), any.routePostId(), any.riderId(),
                    any.driverId());
            byStatus.put(status, sosIncidentRepository.count(spec(one)));
        }
        long all = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new OpsSosDtos.Counts(all, byStatus.get(SosStatus.OPEN), byStatus.get(SosStatus.ACKNOWLEDGED),
                byStatus.get(SosStatus.ESCALATED), byStatus.get(SosStatus.RESOLVED));
    }

    @Transactional(readOnly = true)
    public OpsSosDtos.Detail detail(String incidentId) {
        SosIncident s = sosIncidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS not found"));
        RoutePost route = s.getRoutePostId() == null ? null : routePostRepository.findById(s.getRoutePostId()).orElse(null);
        Booking booking = s.getBookingId() == null ? null : bookingRepository.findById(s.getBookingId()).orElse(null);
        List<TripSafetyAlert> checks = s.getRoutePostId() == null ? List.of()
                : tripSafetyAlertRepository.findByRoutePostIdOrderByCreatedAtAsc(s.getRoutePostId());
        TripSafetyAlert linked = s.getSafetyAlertId() == null ? null
                : checks.stream().filter(a -> a.getId().equals(s.getSafetyAlertId())).findFirst()
                .orElseGet(() -> tripSafetyAlertRepository.findById(s.getSafetyAlertId()).orElse(null));
        List<OpsSosDtos.Realert> realerts = sosRealertRepository.findByIncidentIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(r -> new OpsSosDtos.Realert(r.getLevel(), r.getMinutesOpen(), r.getCreatedAt())).toList();
        return new OpsSosDtos.Detail(row(s, route, booking, linked, Instant.now()), contacts(s.getContactsNotified()),
                linked == null ? null : safetyCheck(linked, route), checks.stream().map(a -> safetyCheck(a, route)).toList(),
                realerts, auditWriter.history(OpsAuditLog.TargetType.SOS, s.getId()), allowed(s));
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    /** Claims it: the console shows who is handling it, and re-alerts stop. */
    @Transactional
    public OpsSosDtos.ActionResult acknowledge(String incidentId, OpsSosDtos.AcknowledgeRequest request) {
        SosIncident s = lock(incidentId);
        SosStatus before = s.getStatus();
        switch (before) {
            case RESOLVED -> throw new BadRequestException("This SOS is already resolved - it can't be acknowledged");
            case ACKNOWLEDGED, ESCALATED -> throw new BadRequestException("This SOS is already being handled by "
                    + Objects.requireNonNullElse(s.getAcknowledgedByName(), "another admin") + " (acknowledged "
                    + s.getAcknowledgedAt() + ")");
            default -> {
            }
        }
        claim(s, request.actorId(), request.actorName());
        s.setStatus(SosStatus.ACKNOWLEDGED);
        return act(s, before, OpsAuditLog.Action.SOS_ACKNOWLEDGED, request.actorId(), request.actorName(),
                request.note() == null || request.note().isBlank() ? "Acknowledged" : request.note(), null);
    }

    /** A note on the incident's timeline - allowed at any point, a resolved incident included (follow-ups happen). */
    @Transactional
    public OpsSosDtos.ActionResult note(String incidentId, OpsSosDtos.NoteRequest request) {
        SosIncident s = lock(incidentId);
        return act(s, s.getStatus(), OpsAuditLog.Action.SOS_NOTE, request.actorId(), request.actorName(), require(request.note(), "note"), null);
    }

    /** Records who ops called (police, ambulance, an emergency contact...), when, and their reference. Can be repeated. */
    @Transactional
    public OpsSosDtos.ActionResult escalate(String incidentId, OpsSosDtos.EscalateRequest request) {
        if (request.target() == null) {
            throw new BadRequestException("target: is required - who was called");
        }
        Instant now = Instant.now();
        Instant contactedAt = request.contactedAt() == null ? now : request.contactedAt();
        if (contactedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new BadRequestException("contactedAt: can't be in the future");
        }
        SosIncident s = lock(incidentId);
        SosStatus before = s.getStatus();
        if (before == SosStatus.RESOLVED) {
            throw new BadRequestException("This SOS is already resolved - it can't be escalated");
        }
        if (s.getCreatedAt() != null && contactedAt.isBefore(s.getCreatedAt().minus(Duration.ofMinutes(1)))) {
            throw new BadRequestException("contactedAt: is before the SOS was raised");
        }
        claim(s, request.actorId(), request.actorName());
        s.setStatus(SosStatus.ESCALATED);
        s.setEscalatedAt(now);
        s.setEscalatedTo(request.target());
        s.setEscalationContact(blankToNull(request.contact()));
        s.setEscalationContactedAt(contactedAt);
        s.setEscalationReference(blankToNull(request.reference()));
        String note = "Called " + request.target() + (request.contact() == null || request.contact().isBlank() ? "" : " (" + request.contact().trim() + ")")
                + (request.reference() == null || request.reference().isBlank() ? "" : ", ref " + request.reference().trim())
                + (request.note() == null || request.note().isBlank() ? "" : " - " + request.note().trim());
        return act(s, before, OpsAuditLog.Action.SOS_ESCALATED, request.actorId(), request.actorName(), note, null);
    }

    @Transactional
    public OpsSosDtos.ActionResult resolve(String incidentId, OpsSosDtos.ResolveRequest request) {
        if (request.outcome() == null) {
            throw new BadRequestException("outcome: is required - SAFE, FALSE_ALARM, ESCALATED_TO_AUTHORITIES or OTHER");
        }
        String note = require(request.note(), "note");
        SosIncident s = lock(incidentId);
        SosStatus before = s.getStatus();
        if (before == SosStatus.RESOLVED) {
            throw new BadRequestException("This SOS is already resolved (" + s.getOutcome() + " by "
                    + Objects.requireNonNullElse(s.getResolvedByName(), "another admin") + ")");
        }
        claim(s, request.actorId(), request.actorName());
        s.setStatus(SosStatus.RESOLVED);
        s.setOutcome(request.outcome());
        s.setResolutionNote(OpsAuditWriter.truncate(note, 1000));
        s.setResolvedAt(Instant.now());
        s.setResolvedBy(request.actorId());
        s.setResolvedByName(name(request.actorId(), request.actorName()));
        return act(s, before, OpsAuditLog.Action.SOS_RESOLVED, request.actorId(), request.actorName(), note, request.outcome());
    }

    // ---- safety checks join the queue ----------------------------------------------------------------------------

    /**
     * An "Are you OK?" check nobody answered (TRIP_SAFETY_ESCALATED) becomes a SAFETY_CHECK incident in the SOS queue,
     * once per check - Kafka redelivery and the unique index on safety_alert_id both make a repeat a no-op.
     */
    @Transactional
    public SosIncident openForSafetyCheck(String alertId) {
        if (alertId == null || sosIncidentRepository.existsBySafetyAlertId(alertId)) {
            return null;
        }
        TripSafetyAlert alert = tripSafetyAlertRepository.findById(alertId).orElse(null);
        if (alert == null) {
            log.warn("Safety check {} escalated but not found - no SOS-queue incident opened", alertId);
            return null;
        }
        SosIncident incident;
        try {
            incident = sosIncidentRepository.saveAndFlush(SosIncident.builder()
                    .kind(SosKind.SAFETY_CHECK)
                    .safetyAlertId(alert.getId())
                    .routePostId(alert.getRoutePostId())
                    .triggeredBy(SosIncident.SYSTEM)
                    .triggeredByRole(SosIncident.SYSTEM)
                    .latitude(alert.getLatitude())
                    .longitude(alert.getLongitude())
                    .opsNotifiedAt(alert.getEscalatedAt() != null ? alert.getEscalatedAt() : Instant.now())
                    .build());
        } catch (DataIntegrityViolationException raced) {
            return null;
        }
        announce(incident, "RAISED", null, null, null);
        eventPublisher.publishEvent(new OpsCountsPublisher.Dirty());
        log.info("Unanswered safety check {} on route {} opened as SOS-queue incident {}", alertId, alert.getRoutePostId(), incident.getId());
        return incident;
    }

    // ---- re-alerts -----------------------------------------------------------------------------------------------

    /**
     * Every OPEN incident past a threshold it hasn't been re-alerted for gets one re-alert, for the highest threshold it
     * has passed (a job that was down doesn't send a burst). claimRealert is a conditional update, so two instances, or an
     * admin acknowledging at the same moment, can't produce a second one. Returns how many were sent.
     */
    @Transactional
    public int realertUnacknowledged() {
        List<Integer> thresholds = properties.thresholds();
        if (thresholds.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int sent = 0;
        for (SosIncident s : sosIncidentRepository.findByStatusAndCreatedAtAfter(SosStatus.OPEN,
                now.minus(Duration.ofHours(properties.getRealertMaxAgeHours())))) {
            if (s.getRealertLevel() >= LEGACY_NO_REALERT) {
                continue;
            }
            long minutesOpen = Duration.between(s.getCreatedAt(), now).toMinutes();
            int level = 0;
            for (int i = 0; i < thresholds.size(); i++) {
                if (minutesOpen >= thresholds.get(i)) {
                    level = i + 1;
                }
            }
            if (level <= s.getRealertLevel() || sosIncidentRepository.claimRealert(s.getId(), level, SosStatus.OPEN) == 0) {
                continue;
            }
            sosRealertRepository.save(SosRealert.builder().incidentId(s.getId()).level(level).minutesOpen((int) minutesOpen).build());
            SosOpsRealertEvent event = realertEvent(s, level, (int) minutesOpen);
            OpsAuditWriter.afterCommit(() -> kafkaTemplate.send(KafkaTopics.SOS_OPS_REALERT, s.getId(), event));
            OpsSosChangedEvent changed = OpsSosChangedEvent.builder().incidentId(s.getId()).kind(s.getKind().name()).action("REALERTED")
                    .status(SosStatus.OPEN.name()).routePostId(s.getRoutePostId()).bookingId(s.getBookingId())
                    .safetyAlertId(s.getSafetyAlertId()).minutesOpen((int) minutesOpen).at(now).build();
            OpsAuditWriter.afterCommit(() -> kafkaTemplate.send(KafkaTopics.OPS_SOS_CHANGED, s.getId(), changed));
            log.warn("SOS {} still unacknowledged after {} min - ops re-alerted (level {})", s.getId(), minutesOpen, level);
            sent++;
        }
        return sent;
    }

    private SosOpsRealertEvent realertEvent(SosIncident s, int level, int minutesOpen) {
        RoutePost route = s.getRoutePostId() == null ? null : routePostRepository.findById(s.getRoutePostId()).orElse(null);
        Booking booking = s.getBookingId() == null ? null : bookingRepository.findById(s.getBookingId()).orElse(null);
        String riderPhone = null;
        String driverName = null;
        String driverPhone = null;
        String plate = null;
        try {
            if (booking != null) {
                riderPhone = identityServiceClient.fetchPhone(booking.getRiderId());
            }
            if (route != null) {
                driverName = profileServiceClient.fetchDriverName(route.getDriverId());
                driverPhone = identityServiceClient.fetchPhone(route.getDriverId());
                plate = route.getVehicleId() == null ? null : profileServiceClient.fetchVehiclePlate(route.getVehicleId());
            }
        } catch (Exception e) {
            log.warn("SOS {} re-alert sent without some contact details: {}", s.getId(), e.getMessage());
        }
        return SosOpsRealertEvent.builder()
                .incidentId(s.getId()).kind(s.getKind().name()).triggeredByRole(s.getTriggeredByRole())
                .routePostId(s.getRoutePostId()).bookingId(s.getBookingId())
                .routeSummary(route == null ? null : route.getOriginLabel() + " → " + route.getDestinationLabel())
                .riderPhone(riderPhone).driverName(driverName).driverPhone(driverPhone).vehiclePlate(plate)
                .latitude(s.getLatitude()).longitude(s.getLongitude())
                .minutesOpen(minutesOpen).level(level)
                .build();
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private SosIncident lock(String incidentId) {
        return sosIncidentRepository.lockById(incidentId).orElseThrow(() -> new ResourceNotFoundException("SOS not found"));
    }

    private static void claim(SosIncident s, String actorId, String actorName) {
        if (s.getAcknowledgedAt() == null) {
            s.setAcknowledgedAt(Instant.now());
            s.setAcknowledgedBy(actorId);
            s.setAcknowledgedByName(name(actorId, actorName));
        }
    }

    private OpsSosDtos.ActionResult act(SosIncident s, SosStatus before, OpsAuditLog.Action action, String actorId, String actorName,
                                        String note, SosOutcome outcome) {
        sosIncidentRepository.save(s);
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("status", before);
        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("status", s.getStatus());
        if (action == OpsAuditLog.Action.SOS_ESCALATED) {
            afterState.put("escalatedTo", s.getEscalatedTo());
            afterState.put("contact", s.getEscalationContact());
            afterState.put("contactedAt", s.getEscalationContactedAt());
            afterState.put("reference", s.getEscalationReference());
        }
        if (outcome != null) {
            afterState.put("outcome", outcome);
        }
        OpsAuditLog audit = auditWriter.write(actorId, actorName, action, OpsAuditLog.TargetType.SOS, s.getId(), s.getRoutePostId(),
                beforeState, afterState, note);
        announce(s, action.name().substring("SOS_".length()), actorId, name(actorId, actorName), outcome);
        eventPublisher.publishEvent(new OpsCountsPublisher.Dirty());
        log.info("OPS {} {} SOS {} ({} -> {})", actorId, action, s.getId(), before, s.getStatus());
        RoutePost route = s.getRoutePostId() == null ? null : routePostRepository.findById(s.getRoutePostId()).orElse(null);
        Booking booking = s.getBookingId() == null ? null : bookingRepository.findById(s.getBookingId()).orElse(null);
        TripSafetyAlert alert = s.getSafetyAlertId() == null ? null : tripSafetyAlertRepository.findById(s.getSafetyAlertId()).orElse(null);
        return new OpsSosDtos.ActionResult(action.name(), audit.getId(), before, s.getStatus(), row(s, route, booking, alert, Instant.now()));
    }

    private void announce(SosIncident s, String action, String actorId, String actorName, SosOutcome outcome) {
        OpsSosChangedEvent event = OpsSosChangedEvent.builder()
                .incidentId(s.getId()).kind(s.getKind().name()).action(action).status(s.getStatus().name())
                .routePostId(s.getRoutePostId()).bookingId(s.getBookingId()).safetyAlertId(s.getSafetyAlertId())
                .actorId(actorId).actorName(actorName).outcome(outcome == null ? null : outcome.name()).at(Instant.now())
                .build();
        OpsAuditWriter.afterCommit(() -> {
            try {
                kafkaTemplate.send(KafkaTopics.OPS_SOS_CHANGED, s.getId(), event);
            } catch (Exception e) {
                log.warn("Could not announce SOS {} {}: {}", s.getId(), action, e.getMessage());
            }
        });
    }

    private static OpsSosDtos.Allowed allowed(SosIncident s) {
        boolean live = s.getStatus() != SosStatus.RESOLVED;
        return new OpsSosDtos.Allowed(s.getStatus() == SosStatus.OPEN, true, live, live);
    }

    OpsSosDtos.Row row(SosIncident s, RoutePost route, Booking booking, TripSafetyAlert alert, Instant now) {
        Long minutesOpen = s.getStatus() == SosStatus.RESOLVED || s.getCreatedAt() == null ? null
                : Duration.between(s.getCreatedAt(), now).toMinutes();
        int realerts = s.getRealertLevel() >= LEGACY_NO_REALERT ? 0 : s.getRealertLevel();
        return new OpsSosDtos.Row(s.getId(), s.getKind(), s.getStatus(), s.getCreatedAt(), s.getTriggeredBy(), s.getTriggeredByRole(),
                s.getBookingId(), booking == null ? null : booking.getRiderId(), s.getRoutePostId(),
                route == null ? null : route.getDriverId(), route == null ? null : route.getVehicleId(),
                route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(),
                route == null ? null : route.getStatus(), route == null ? null : route.getDepartureTime(),
                s.getLatitude(), s.getLongitude(), s.getOpsNotifiedAt(), s.getSafetyAlertId(),
                alert == null ? null : alert.getKind().name(),
                s.getAcknowledgedAt(), s.getAcknowledgedBy(), s.getAcknowledgedByName(),
                s.getEscalatedAt(), s.getEscalatedTo(), s.getEscalationContact(), s.getEscalationContactedAt(), s.getEscalationReference(),
                s.getResolvedAt(), s.getResolvedBy(), s.getResolvedByName(), s.getOutcome(), s.getResolutionNote(),
                realerts, minutesOpen);
    }

    private static OpsSosDtos.SafetyCheck safetyCheck(TripSafetyAlert a, RoutePost route) {
        List<OpsSosDtos.Answer> answers = a.getAnswers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new OpsSosDtos.Answer(e.getKey(),
                        route != null && e.getKey().equals(route.getDriverId()) ? "DRIVER" : "RIDER", e.getValue().name()))
                .sorted((x, y) -> x.role().equals(y.role()) ? 0 : "DRIVER".equals(x.role()) ? -1 : 1)
                .toList();
        return new OpsSosDtos.SafetyCheck(a.getId(), a.getKind().name(), a.getStatus().name(), a.getCreatedAt(), a.getEscalatedAt(),
                a.getLatitude(), a.getLongitude(), answers);
    }

    private static List<OpsSosDtos.Contact> contacts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OpsAuditWriter.JSON.readValue(json, new TypeReference<List<OpsSosDtos.Contact>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The JSON kept on an SOS of the emergency contacts it texted. */
    public static String contactsJson(Collection<OpsSosDtos.Contact> contacts) {
        return contacts == null || contacts.isEmpty() ? null : OpsAuditWriter.json(contacts);
    }

    private Map<String, RoutePost> routes(List<SosIncident> incidents) {
        List<String> ids = incidents.stream().map(SosIncident::getRoutePostId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : routePostRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(RoutePost::getId, Function.identity()));
    }

    private Map<String, Booking> bookings(List<SosIncident> incidents) {
        List<String> ids = incidents.stream().map(SosIncident::getBookingId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : bookingRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
    }

    private Map<String, TripSafetyAlert> alerts(List<SosIncident> incidents) {
        List<String> ids = incidents.stream().map(SosIncident::getSafetyAlertId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : tripSafetyAlertRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TripSafetyAlert::getId, Function.identity()));
    }

    private static Specification<SosIncident> spec(OpsSosDtos.Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.kind() != null) {
                where.add(cb.equal(root.get("kind"), filter.kind()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOfLagosDay(filter.from())));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), startOfLagosDay(filter.to().plusDays(1))));
            }
            if (notBlank(filter.routePostId())) {
                where.add(cb.equal(root.get("routePostId"), filter.routePostId().trim()));
            }
            if (notBlank(filter.driverId())) {
                Subquery<String> driven = query.subquery(String.class);
                var route = driven.from(RoutePost.class);
                driven.select(route.get("id")).where(cb.equal(route.get("id"), root.get("routePostId")),
                        cb.equal(route.get("driverId"), filter.driverId().trim()));
                where.add(cb.exists(driven));
            }
            if (notBlank(filter.riderId())) {
                String rider = filter.riderId().trim();
                Subquery<String> ownSeat = query.subquery(String.class);
                var seat = ownSeat.from(Booking.class);
                ownSeat.select(seat.get("id")).where(cb.equal(seat.get("id"), root.get("bookingId")), cb.equal(seat.get("riderId"), rider));
                Subquery<String> onTrip = query.subquery(String.class);
                var paid = onTrip.from(Booking.class);
                onTrip.select(paid.get("id")).where(cb.equal(paid.get("routePostId"), root.get("routePostId")),
                        cb.equal(paid.get("riderId"), rider), cb.isNotNull(paid.get("confirmedAt")));
                where.add(cb.or(cb.equal(root.get("triggeredBy"), rider), cb.exists(ownSeat),
                        cb.and(cb.isNull(root.get("bookingId")), cb.exists(onTrip))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    static Instant startOfLagosDay(LocalDate day) {
        return day.atStartOfDay(LAGOS).toInstant();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + ": is required");
        }
        return value.trim();
    }

    static String name(String actorId, String actorName) {
        return actorName == null || actorName.isBlank() ? actorId : actorName.trim();
    }
}

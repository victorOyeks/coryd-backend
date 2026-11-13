package org.coryd.trip.service;

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
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IssueReportUpdatedEvent;
import org.coryd.common.kafka.events.OpsCountsChangedEvent;
import org.coryd.common.kafka.events.OpsSosChangedEvent;
import org.coryd.common.kafka.events.SosOpsRealertEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.SosOpsProperties;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.dto.ops.OpsRatingDtos;
import org.coryd.trip.dto.ops.OpsReportDtos;
import org.coryd.trip.dto.ops.OpsSosDtos;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.coryd.trip.repository.SosRealertRepository;
import org.coryd.trip.repository.TripSafetyAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ops Console phase 5 against a throwaway Postgres built from trip-service's own migrations: the SOS queue (acknowledge,
 * note, escalate, resolve, re-alerts once per threshold, unanswered safety checks joining it), reports & disputes, ratings
 * moderation and the live badge counts. Kafka is mocked - what was sent is the contract with ops- and notification-service.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({OpsSosService.class, OpsIssueReportService.class, OpsRatingService.class, OpsAuditWriter.class, SosOpsProperties.class,
        OpsCountsPublisher.class, StuckTripProperties.class})
class OpsSafetySupportIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-1";
    private static final String OTHER_DRIVER = "driver-2";
    private static final String RIDER = "rider-1";
    private static final String OTHER_RIDER = "rider-2";
    private static final String ADMIN = "admin-1";
    private static final String OTHER_ADMIN = "admin-2";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired OpsSosService sosService;
    @Autowired OpsIssueReportService reportService;
    @Autowired OpsRatingService ratingService;
    @Autowired OpsCountsPublisher countsPublisher;
    @Autowired SosOpsProperties sosProperties;
    @Autowired SosIncidentRepository sosRepository;
    @Autowired SosRealertRepository realertRepository;
    @Autowired TripSafetyAlertRepository alertRepository;
    @Autowired IssueReportRepository reportRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired RoutePostRepository routeRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired OpsAuditLogRepository auditRepository;
    @Autowired JdbcTemplate jdbc;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean ProfileServiceClient profileServiceClient;
    @MockBean DriverStatsPublisher driverStatsPublisher;
    @MockBean RiderStatsPublisher riderStatsPublisher;

    @BeforeEach
    void cleanSlate() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(postgres.getDatabaseName());
        realertRepository.deleteAll();
        sosRepository.deleteAll();
        alertRepository.deleteAll();
        reportRepository.deleteAll();
        ratingRepository.deleteAll();
        auditRepository.deleteAll();
        bookingRepository.deleteAll();
        routeRepository.deleteAll();
        sosProperties.setRealertMinutes(new ArrayList<>(List.of(5, 15, 30)));
        sosProperties.setRealertMaxAgeHours(12);
        clearInvocations(kafkaTemplate, driverStatsPublisher, riderStatsPublisher);
        when(identityServiceClient.fetchPhone(anyString())).thenAnswer(i -> "080" + i.getArgument(0).hashCode());
        when(profileServiceClient.fetchDriverName(anyString())).thenReturn("Emeka Okafor");
        when(profileServiceClient.fetchVehiclePlate(anyString())).thenReturn("LND-441-AA");
    }

    // ---- SOS: the whole flow -------------------------------------------------------------------------------------

    @Test
    void anSosIsAcknowledgedNotedEscalatedAndResolvedOnceEachStepAuditedAndAnnounced() {
        RoutePost route = route(DRIVER);
        Booking booking = seat(route, RIDER);
        SosIncident sos = sos(route, booking, RIDER, "RIDER");

        assertThat(sosService.counts(filter(null)).open()).isEqualTo(1);
        OpsSosDtos.ActionResult ack = sosService.acknowledge(sos.getId(), new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops"));
        assertThat(ack.statusBefore()).isEqualTo(SosStatus.OPEN);
        assertThat(ack.statusAfter()).isEqualTo(SosStatus.ACKNOWLEDGED);
        assertThat(ack.incident().acknowledgedBy()).isEqualTo(ADMIN);
        assertThat(ack.incident().acknowledgedByName()).isEqualTo("Ada Ops");
        assertThat(ack.incident().riderId()).isEqualTo(RIDER);
        assertThat(ack.incident().driverId()).isEqualTo(DRIVER);

        assertThatThrownBy(() -> sosService.acknowledge(sos.getId(), new OpsSosDtos.AcknowledgeRequest(null, OTHER_ADMIN, "Bola")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already being handled by Ada Ops");
        assertThatThrownBy(() -> sosService.note(sos.getId(), new OpsSosDtos.NoteRequest("  ", ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("note");
        sosService.note(sos.getId(), new OpsSosDtos.NoteRequest("Rider not answering - trying driver", ADMIN, "Ada Ops"));

        assertThatThrownBy(() -> sosService.escalate(sos.getId(), new OpsSosDtos.EscalateRequest(null, null, null, null, null, ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("target");
        assertThatThrownBy(() -> sosService.escalate(sos.getId(), new OpsSosDtos.EscalateRequest(SosEscalationTarget.POLICE, null,
                Instant.now().plus(2, ChronoUnit.HOURS), null, null, ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("future");
        Instant called = Instant.now().minus(1, ChronoUnit.MINUTES);
        OpsSosDtos.ActionResult escalated = sosService.escalate(sos.getId(), new OpsSosDtos.EscalateRequest(SosEscalationTarget.POLICE,
                "Ikeja division, Sgt Bello", called, "IKJ-2291", "Car seen on Ikorodu Rd", ADMIN, "Ada Ops"));
        assertThat(escalated.statusAfter()).isEqualTo(SosStatus.ESCALATED);
        assertThat(escalated.incident().escalatedTo()).isEqualTo(SosEscalationTarget.POLICE);
        assertThat(escalated.incident().escalationReference()).isEqualTo("IKJ-2291");
        assertThat(escalated.incident().escalationContactedAt()).isCloseTo(called, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));

        assertThatThrownBy(() -> sosService.resolve(sos.getId(), new OpsSosDtos.ResolveRequest(null, "fine", ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> sosService.resolve(sos.getId(), new OpsSosDtos.ResolveRequest(SosOutcome.SAFE, " ", ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("note");
        OpsSosDtos.ActionResult resolved = sosService.resolve(sos.getId(),
                new OpsSosDtos.ResolveRequest(SosOutcome.ESCALATED_TO_AUTHORITIES, "Police met the car at Ojota", OTHER_ADMIN, "Bola"));
        assertThat(resolved.statusAfter()).isEqualTo(SosStatus.RESOLVED);
        assertThat(resolved.incident().resolvedByName()).isEqualTo("Bola");
        assertThat(resolved.incident().acknowledgedByName()).as("the claim stays with whoever acknowledged it").isEqualTo("Ada Ops");
        assertThat(resolved.incident().minutesOpen()).isNull();

        assertThatThrownBy(() -> sosService.resolve(sos.getId(), new OpsSosDtos.ResolveRequest(SosOutcome.SAFE, "again", ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already resolved");
        assertThatThrownBy(() -> sosService.acknowledge(sos.getId(), new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already resolved");
        assertThatThrownBy(() -> sosService.escalate(sos.getId(), new OpsSosDtos.EscalateRequest(SosEscalationTarget.AMBULANCE, null, null,
                null, null, ADMIN, "Ada Ops"))).isInstanceOf(BadRequestException.class).hasMessageContaining("already resolved");
        sosService.note(sos.getId(), new OpsSosDtos.NoteRequest("Rider called back to thank us", ADMIN, "Ada Ops"));
        assertThatThrownBy(() -> sosService.acknowledge("nope", new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops")))
                .isInstanceOf(ResourceNotFoundException.class);

        List<OpsAuditLog> audit = auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.SOS, sos.getId());
        assertThat(audit).extracting(OpsAuditLog::getAction).containsExactly(OpsAuditLog.Action.SOS_ACKNOWLEDGED, OpsAuditLog.Action.SOS_NOTE,
                OpsAuditLog.Action.SOS_ESCALATED, OpsAuditLog.Action.SOS_RESOLVED, OpsAuditLog.Action.SOS_NOTE);
        assertThat(audit).allMatch(a -> a.getRoutePostId().equals(route.getId()));
        assertThat(audit.get(2).getAfterState()).contains("POLICE").contains("IKJ-2291");
        assertThat(audit.get(3).getBeforeState()).contains("ESCALATED");
        assertThat(audit.get(3).getAfterState()).contains("RESOLVED").contains("ESCALATED_TO_AUTHORITIES");
        assertThat(audit.get(3).getActorName()).isEqualTo("Bola");
        assertThat(auditRepository.findByRoutePostIdOrderByCreatedAtAsc(route.getId())).as("on the trip's timeline as OPS_ACTION").hasSize(5);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeast(5)).send(eq(KafkaTopics.OPS_SOS_CHANGED), eq(sos.getId()), sent.capture());
        assertThat(sent.getAllValues()).map(e -> ((OpsSosChangedEvent) e).getAction())
                .containsExactly("ACKNOWLEDGED", "NOTE", "ESCALATED", "RESOLVED", "NOTE");

        OpsSosDtos.Detail detail = sosService.detail(sos.getId());
        assertThat(detail.actions()).hasSize(5);
        assertThat(detail.contactsNotified()).containsExactly(new OpsSosDtos.Contact("Mum", "08011110000"));
        assertThat(detail.allowed()).isEqualTo(new OpsSosDtos.Allowed(false, true, false, false));
    }

    @Test
    void twoAdminsAcknowledgingAtOnceOnlyOneWins() throws Exception {
        SosIncident sos = sos(route(DRIVER), null, DRIVER, "DRIVER");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (String admin : List.of(ADMIN, OTHER_ADMIN)) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    sosService.acknowledge(sos.getId(), new OpsSosDtos.AcknowledgeRequest(null, admin, admin));
                    return "ok";
                } catch (BadRequestException e) {
                    return "refused";
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).containsExactlyInAnyOrder("ok", "refused");
        assertThat(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.SOS, sos.getId())).hasSize(1);
    }

    @Test
    void theQueueFiltersByStatusTripRiderDriverAndLagosDays() {
        RoutePost mine = route(DRIVER);
        RoutePost theirs = route(OTHER_DRIVER);
        SosIncident riderSos = sos(mine, seat(mine, RIDER), RIDER, "RIDER");
        seat(mine, OTHER_RIDER);
        SosIncident driverSos = sos(mine, null, DRIVER, "DRIVER");
        SosIncident otherSos = sos(theirs, seat(theirs, OTHER_RIDER), OTHER_RIDER, "RIDER");
        sosService.acknowledge(otherSos.getId(), new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops"));
        // Raised "yesterday" in Lagos.
        jdbc.update("update trip.sos_incidents set created_at = created_at - interval '1 day' where id = ?", driverSos.getId());

        assertThat(ids(sosService.list(filter(SosStatus.OPEN), PageRequest.of(0, 20)).content()))
                .containsExactlyInAnyOrder(riderSos.getId(), driverSos.getId());
        assertThat(ids(sosService.list(new OpsSosDtos.Filter(null, null, null, null, null, null, OTHER_DRIVER), PageRequest.of(0, 20)).content()))
                .containsExactly(otherSos.getId());
        assertThat(ids(sosService.list(new OpsSosDtos.Filter(null, null, null, null, mine.getId(), null, null), PageRequest.of(0, 20)).content()))
                .containsExactlyInAnyOrder(riderSos.getId(), driverSos.getId());
        assertThat(ids(sosService.list(new OpsSosDtos.Filter(null, null, null, null, null, OTHER_RIDER, null), PageRequest.of(0, 20)).content()))
                .as("a rider's own SOS, and a driver's SOS on a trip they'd paid a seat on")
                .containsExactlyInAnyOrder(otherSos.getId(), driverSos.getId());
        LocalDate today = LocalDate.now(LAGOS);
        assertThat(ids(sosService.list(new OpsSosDtos.Filter(null, null, today, today, null, null, null), PageRequest.of(0, 20)).content()))
                .containsExactlyInAnyOrder(riderSos.getId(), otherSos.getId());

        OpsSosDtos.Counts counts = sosService.counts(filter(null));
        assertThat(counts).isEqualTo(new OpsSosDtos.Counts(3, 2, 1, 0, 0));
        assertThat(sosService.counts(new OpsSosDtos.Filter(null, SosKind.SAFETY_CHECK, null, null, null, null, null)).all()).isZero();
    }

    // ---- re-alerts -----------------------------------------------------------------------------------------------

    @Test
    void anUnacknowledgedSosReAlertsOpsOncePerThresholdAndStopsOnceAcknowledged() {
        RoutePost route = route(DRIVER);
        SosIncident sos = sos(route, seat(route, RIDER), RIDER, "RIDER");
        SosIncident legacy = sos(route, null, DRIVER, "DRIVER");
        jdbc.update("update trip.sos_incidents set realert_level = 99 where id = ?", legacy.getId());
        SosIncident handled = sos(route, null, DRIVER, "DRIVER");
        sosService.acknowledge(handled.getId(), new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops"));
        backdate(sos, 3);
        backdate(legacy, 20);
        backdate(handled, 20);
        clearInvocations(kafkaTemplate);

        assertThat(sosService.realertUnacknowledged()).as("under the first threshold").isZero();

        backdate(sos, 6);
        assertThat(sosService.realertUnacknowledged()).isEqualTo(1);
        assertThat(sosService.realertUnacknowledged()).as("the 5-minute threshold fires once").isZero();
        backdate(sos, 16);
        assertThat(sosService.realertUnacknowledged()).isEqualTo(1);
        assertThat(sosService.realertUnacknowledged()).isZero();

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.SOS_OPS_REALERT), eq(sos.getId()), sent.capture());
        SosOpsRealertEvent first = (SosOpsRealertEvent) sent.getAllValues().get(0);
        assertThat(first.getLevel()).isEqualTo(1);
        assertThat(first.getMinutesOpen()).isEqualTo(6);
        assertThat(first.getDriverName()).isEqualTo("Emeka Okafor");
        assertThat(first.getVehiclePlate()).isEqualTo("LND-441-AA");
        assertThat(first.getRiderPhone()).isNotNull();
        assertThat(first.getRouteSummary()).isEqualTo("Ikorodu → Victoria Island");
        assertThat(((SosOpsRealertEvent) sent.getAllValues().get(1)).getLevel()).isEqualTo(2);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.SOS_OPS_REALERT), eq(legacy.getId()), any());
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.SOS_OPS_REALERT), eq(handled.getId()), any());
        assertThat(realertRepository.findByIncidentIdOrderByCreatedAtAsc(sos.getId())).extracting(r -> r.getLevel()).containsExactly(1, 2);
        assertThat(sosService.detail(sos.getId()).incident().realertsSent()).isEqualTo(2);

        sosService.acknowledge(sos.getId(), new OpsSosDtos.AcknowledgeRequest("On it", ADMIN, "Ada Ops"));
        backdate(sos, 45);
        clearInvocations(kafkaTemplate);
        assertThat(sosService.realertUnacknowledged()).as("acknowledged - no more re-alerts").isZero();
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.SOS_OPS_REALERT), anyString(), any());
    }

    @Test
    void aJobThatWasDownSendsOneReAlertForTheHighestThresholdNotABurst() {
        RoutePost route = route(DRIVER);
        SosIncident sos = sos(route, null, DRIVER, "DRIVER");
        backdate(sos, 40);
        assertThat(sosService.realertUnacknowledged()).isEqualTo(1);
        assertThat(sosService.realertUnacknowledged()).isZero();
        assertThat(realertRepository.findByIncidentIdOrderByCreatedAtAsc(sos.getId())).extracting(r -> r.getLevel()).containsExactly(3);

        SosIncident stale = sos(route, null, DRIVER, "DRIVER");
        backdate(stale, 13 * 60);
        assertThat(sosService.realertUnacknowledged()).as("older than realert-max-age-hours").isZero();
    }

    // ---- unanswered safety checks join the queue -----------------------------------------------------------------

    @Test
    void anUnansweredSafetyCheckOpensOneSafetyCheckIncidentWorkedLikeAnSos() {
        RoutePost route = route(DRIVER);
        seat(route, RIDER);
        TripSafetyAlert alert = TripSafetyAlert.builder().routePostId(route.getId()).kind(TripSafetyAlert.Kind.STOPPED)
                .status(TripSafetyAlert.Status.ESCALATED).escalatedAt(Instant.now()).latitude(6.5).longitude(3.4).build();
        alert.getAnswers().put(DRIVER, TripSafetyAlert.Answer.FINE);
        alert.getAnswers().put(RIDER, TripSafetyAlert.Answer.WAITING);
        alertRepository.save(alert);

        SosIncident incident = sosService.openForSafetyCheck(alert.getId());
        assertThat(incident.getKind()).isEqualTo(SosKind.SAFETY_CHECK);
        assertThat(incident.getTriggeredBy()).isEqualTo(SosIncident.SYSTEM);
        assertThat(sosService.openForSafetyCheck(alert.getId())).as("once per check").isNull();
        assertThat(sosRepository.count()).isEqualTo(1);
        verify(kafkaTemplate).send(eq(KafkaTopics.OPS_SOS_CHANGED), eq(incident.getId()),
                org.mockito.ArgumentMatchers.argThat(e -> "RAISED".equals(((OpsSosChangedEvent) e).getAction())
                        && "SAFETY_CHECK".equals(((OpsSosChangedEvent) e).getKind())));

        OpsSosDtos.Detail detail = sosService.detail(incident.getId());
        assertThat(detail.incident().safetyCheckKind()).isEqualTo("STOPPED");
        assertThat(detail.safetyCheck().answers()).extracting(OpsSosDtos.Answer::role, OpsSosDtos.Answer::answer)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DRIVER", "FINE"), org.assertj.core.groups.Tuple.tuple("RIDER", "WAITING"));
        assertThat(sosService.list(new OpsSosDtos.Filter(SosStatus.OPEN, SosKind.SAFETY_CHECK, null, null, null, null, null),
                PageRequest.of(0, 20)).content()).hasSize(1);

        TripSafetyAlert old = alertRepository.save(TripSafetyAlert.builder().routePostId(route.getId()).kind(TripSafetyAlert.Kind.SIGNAL_LOST)
                .status(TripSafetyAlert.Status.ESCALATED).escalatedAt(Instant.now().minus(2, ChronoUnit.HOURS)).build());
        assertThat(sosService.openForSafetyCheck(old.getId())).as("a replayed old escalation isn't news").isNull();

        sosService.acknowledge(incident.getId(), new OpsSosDtos.AcknowledgeRequest(null, ADMIN, "Ada Ops"));
        sosService.resolve(incident.getId(), new OpsSosDtos.ResolveRequest(SosOutcome.SAFE, "Rider's phone was off; driver confirmed all fine",
                ADMIN, "Ada Ops"));
        assertThat(sosRepository.findById(incident.getId()).orElseThrow().getStatus()).isEqualTo(SosStatus.RESOLVED);
    }

    // ---- reports & disputes --------------------------------------------------------------------------------------

    @Test
    void aReportIsAssignedNotedAndResolvedOnceWithTheReporterTold() {
        RoutePost route = route(DRIVER);
        Booking booking = seat(route, RIDER);
        IssueReport report = report(booking, RIDER, "RIDER", IssueCategory.DRIVER_CONDUCT, "OPEN");
        IssueReport earlier = report(seat(route(DRIVER), OTHER_RIDER), OTHER_RIDER, "RIDER", IssueCategory.DRIVER_CONDUCT, "RESOLVED");
        report(null, "someone-else", "RIDER", IssueCategory.OTHER, "OPEN");

        OpsReportDtos.ActionResult assigned = reportService.assign(report.getId(), new OpsReportDtos.AssignRequest(null, null, null, ADMIN, "Ada Ops"));
        assertThat(assigned.statusBefore()).isEqualTo(IssueReportStatus.OPEN);
        assertThat(assigned.statusAfter()).isEqualTo(IssueReportStatus.IN_PROGRESS);
        assertThat(assigned.report().assignedTo()).isEqualTo(ADMIN);
        reportService.assign(report.getId(), new OpsReportDtos.AssignRequest(OTHER_ADMIN, "Bola", "Bola handles conduct", ADMIN, "Ada Ops"));
        reportService.note(report.getId(), new OpsReportDtos.NoteRequest("Called the driver - denies it", OTHER_ADMIN, "Bola"));

        assertThatThrownBy(() -> reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(null, "x", "y", null, null, ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(IssueReportOutcome.WARNING, "", "y",
                null, null, ADMIN, "Ada"))).isInstanceOf(BadRequestException.class).hasMessageContaining("note");
        assertThatThrownBy(() -> reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(IssueReportOutcome.WARNING, "x", " ",
                null, null, ADMIN, "Ada"))).isInstanceOf(BadRequestException.class).hasMessageContaining("messageToReporter");
        assertThatThrownBy(() -> reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(IssueReportOutcome.SUSPENDED, "x", "y",
                null, DRIVER, ADMIN, "Ada"))).isInstanceOf(BadRequestException.class).hasMessageContaining("actionType");

        OpsReportDtos.ActionResult resolved = reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(IssueReportOutcome.SUSPENDED,
                "Third conduct report this month", "Thanks for telling us. We've taken action on this driver's account.",
                IssueReportActionType.SUSPENSION, DRIVER, OTHER_ADMIN, "Bola"));
        assertThat(resolved.statusAfter()).isEqualTo(IssueReportStatus.RESOLVED);
        assertThat(resolved.report().actionReference()).isEqualTo(DRIVER);
        assertThat(resolved.report().resolvedByName()).isEqualTo("Bola");
        ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.ISSUE_REPORT_UPDATED), eq(RIDER), told.capture());
        IssueReportUpdatedEvent event = (IssueReportUpdatedEvent) told.getValue();
        assertThat(event.getMessage()).isEqualTo("Thanks for telling us. We've taken action on this driver's account.");
        assertThat(event.getStatus()).isEqualTo("RESOLVED");
        assertThat(event.getOutcome()).isEqualTo("SUSPENDED");

        assertThatThrownBy(() -> reportService.resolve(report.getId(), new OpsReportDtos.ResolveRequest(IssueReportOutcome.WARNING, "x", "y",
                null, null, ADMIN, "Ada"))).isInstanceOf(BadRequestException.class).hasMessageContaining("already resolved");
        assertThatThrownBy(() -> reportService.dismiss(report.getId(), new OpsReportDtos.DismissRequest("x", null, ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already resolved");
        assertThatThrownBy(() -> reportService.assign(report.getId(), new OpsReportDtos.AssignRequest(null, null, null, ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class);

        OpsReportDtos.Detail detail = reportService.detail(report.getId());
        assertThat(detail.report().driverId()).isEqualTo(DRIVER);
        assertThat(detail.report().riderId()).isEqualTo(RIDER);
        assertThat(detail.priorReports()).as("other reports on the same people").extracting(OpsReportDtos.Row::id).containsExactly(earlier.getId());
        assertThat(detail.actions()).extracting(a -> a.action()).containsExactly("REPORT_ASSIGNED", "REPORT_ASSIGNED", "REPORT_NOTE", "REPORT_RESOLVED");
        assertThat(detail.actions().get(1).note()).contains("Assigned to Bola");
        assertThat(detail.allowed()).isEqualTo(new OpsReportDtos.Allowed(false, true, false, false));
        assertThat(detail.attachments()).isEmpty();
        assertThat(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.ISSUE, report.getId()))
                .allMatch(a -> route.getId().equals(a.getRoutePostId()));
    }

    @Test
    void aDismissedReportOnlyTellsTheReporterWhenOpsWroteToThem() {
        IssueReport quiet = report(null, RIDER, "RIDER", IssueCategory.OTHER, "OPEN");
        IssueReport told = report(null, OTHER_RIDER, "RIDER", IssueCategory.OTHER, "OPEN");
        assertThatThrownBy(() -> reportService.dismiss(quiet.getId(), new OpsReportDtos.DismissRequest(" ", null, ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("note");

        reportService.dismiss(quiet.getId(), new OpsReportDtos.DismissRequest("Duplicate of ISS-1", null, ADMIN, "Ada"));
        reportService.dismiss(told.getId(), new OpsReportDtos.DismissRequest("Test report", "This looked like a test - no action needed.", ADMIN, "Ada"));

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.ISSUE_REPORT_UPDATED), eq(RIDER), any());
        verify(kafkaTemplate).send(eq(KafkaTopics.ISSUE_REPORT_UPDATED), eq(OTHER_RIDER), any());
        assertThat(OpsIssueReportService.status(reportRepository.findById(quiet.getId()).orElseThrow())).isEqualTo(IssueReportStatus.DISMISSED);
        assertThatThrownBy(() -> reportService.dismiss(quiet.getId(), new OpsReportDtos.DismissRequest("again", null, ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already dismissed");
    }

    @Test
    void reportsFilterByStatusCategoryReporterRoleTripAndPerson() {
        RoutePost route = route(DRIVER);
        Booking booking = seat(route, RIDER);
        IssueReport byRider = report(booking, RIDER, "RIDER", IssueCategory.PAYMENT, "OPEN");
        IssueReport byDriver = report(booking, DRIVER, "DRIVER", IssueCategory.RIDER_CONDUCT, "IN_REVIEW");
        IssueReport elsewhere = report(null, OTHER_RIDER, "RIDER", IssueCategory.OTHER, "RESOLVED");

        assertThat(ids(reportService.list(reportFilter(IssueReportStatus.IN_PROGRESS, null, null, null, null), PageRequest.of(0, 20)).content(), true))
                .as("the old IN_REVIEW reads as IN_PROGRESS").containsExactly(byDriver.getId());
        assertThat(ids(reportService.list(reportFilter(null, IssueCategory.PAYMENT, null, null, null), PageRequest.of(0, 20)).content(), true))
                .containsExactly(byRider.getId());
        assertThat(ids(reportService.list(reportFilter(null, null, "driver", null, null), PageRequest.of(0, 20)).content(), true))
                .containsExactly(byDriver.getId());
        assertThat(ids(reportService.list(reportFilter(null, null, null, route.getId(), null), PageRequest.of(0, 20)).content(), true))
                .containsExactlyInAnyOrder(byRider.getId(), byDriver.getId());
        assertThat(ids(reportService.list(reportFilter(null, null, null, null, DRIVER), PageRequest.of(0, 20)).content(), true))
                .as("filed by the driver or about a trip they drove").containsExactlyInAnyOrder(byRider.getId(), byDriver.getId());
        assertThat(reportService.counts(reportFilter(null, null, null, null, null)))
                .isEqualTo(new OpsReportDtos.Counts(3, 1, 1, 1, 0));
        assertThat(elsewhere).isNotNull();
    }

    // ---- ratings moderation --------------------------------------------------------------------------------------

    @Test
    void aHiddenRatingCountsInNoAverageAndRestoringBringsItBack() {
        RoutePost route = route(DRIVER);
        Rating five = rating(seat(route, RIDER), RIDER, DRIVER, 5, "Lovely ride");
        Rating one = rating(seat(route, OTHER_RIDER), OTHER_RIDER, DRIVER, 1, "Rude and you are a thief");
        assertThat(average(DRIVER)).isEqualTo(3.0);

        assertThatThrownBy(() -> ratingService.hide(one.getId(), new OpsRatingDtos.ModerationRequest(" ", ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        OpsRatingDtos.ActionResult hidden = ratingService.hide(one.getId(), new OpsRatingDtos.ModerationRequest("Abusive", ADMIN, "Ada Ops"));
        assertThat(hidden.rating().hidden()).isTrue();
        assertThat(hidden.rating().hiddenByName()).isEqualTo("Ada Ops");
        assertThat(hidden.rateeSummary().ratingAvg()).isEqualTo(5.0);
        assertThat(hidden.rateeSummary().ratingCount()).isEqualTo(1);
        assertThat(hidden.rateeSummary().hiddenCount()).isEqualTo(1);
        assertThat(hidden.rateeSummary().distribution()).containsEntry("1", 0L).containsEntry("5", 1L);
        assertThat(average(DRIVER)).as("every average comes from summarize").isEqualTo(5.0);
        verify(driverStatsPublisher).changed(DRIVER);

        assertThatThrownBy(() -> ratingService.hide(one.getId(), new OpsRatingDtos.ModerationRequest("again", ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already hidden");
        assertThatThrownBy(() -> ratingService.restore(five.getId(), new OpsRatingDtos.ModerationRequest("why", ADMIN, "Ada")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("isn't hidden");

        assertThat(ratingService.list(new OpsRatingDtos.Filter(2, null, true, null, null, null, null, null, null), PageRequest.of(0, 20))
                .content()).singleElement().satisfies(r -> {
                    assertThat(r.id()).isEqualTo(one.getId());
                    assertThat(r.hidden()).isTrue();
                    assertThat(r.hiddenReason()).isEqualTo("Abusive");
                    assertThat(r.routePostId()).isEqualTo(route.getId());
                });
        assertThat(ratingService.list(new OpsRatingDtos.Filter(null, null, null, false, null, DRIVER, null, null, null), PageRequest.of(0, 20))
                .content()).extracting(OpsRatingDtos.Row::id).containsExactly(five.getId());

        OpsRatingDtos.ActionResult restored = ratingService.restore(one.getId(), new OpsRatingDtos.ModerationRequest("Rider appealed", ADMIN, "Ada"));
        assertThat(restored.rating().hidden()).isFalse();
        assertThat(restored.rateeSummary().ratingAvg()).isEqualTo(3.0);
        assertThat(average(DRIVER)).isEqualTo(3.0);
        assertThat(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.RATING, one.getId()))
                .extracting(OpsAuditLog::getAction).containsExactly(OpsAuditLog.Action.RATING_HIDDEN, OpsAuditLog.Action.RATING_RESTORED);

        Rating ofRider = rating(seat(route, "rider-3"), DRIVER, "rider-3", 2, "Late");
        ofRider.setDirection(RatingDirection.DRIVER_TO_RIDER);
        ratingRepository.save(ofRider);
        ratingService.hide(ofRider.getId(), new OpsRatingDtos.ModerationRequest("Retaliatory", ADMIN, "Ada"));
        verify(riderStatsPublisher).changed("rider-3");
        assertThatThrownBy(() -> ratingService.list(new OpsRatingDtos.Filter(9, null, null, null, null, null, null, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- live badges ---------------------------------------------------------------------------------------------

    @Test
    void theBadgeCountsAreSentWhenTheyChangeAndNotOtherwise() {
        RoutePost route = route(DRIVER);
        sos(route, null, DRIVER, "DRIVER");
        report(null, RIDER, "RIDER", IssueCategory.OTHER, "OPEN");
        OpsCountsChangedEvent first = countsPublisher.publishIfChanged(true);
        assertThat(first.getSosOpen()).isEqualTo(1);
        assertThat(first.getReportsOpen()).isEqualTo(1);
        assertThat(first.getStuckTrips()).isZero();
        clearInvocations(kafkaTemplate);

        countsPublisher.publishIfChanged(false);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.OPS_COUNTS_CHANGED), anyString(), any());

        sos(route, null, DRIVER, "DRIVER");
        countsPublisher.publishIfChanged(false);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.OPS_COUNTS_CHANGED), anyString(),
                org.mockito.ArgumentMatchers.argThat(e -> ((OpsCountsChangedEvent) e).getSosOpen() == 2));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static OpsSosDtos.Filter filter(SosStatus status) {
        return new OpsSosDtos.Filter(status, null, null, null, null, null, null);
    }

    private static OpsReportDtos.Filter reportFilter(IssueReportStatus status, IssueCategory category, String role, String routePostId, String userId) {
        return new OpsReportDtos.Filter(status, category, role, null, null, routePostId, null, userId, null);
    }

    private static List<String> ids(List<OpsSosDtos.Row> rows) {
        return rows.stream().map(OpsSosDtos.Row::id).toList();
    }

    private static List<String> ids(List<OpsReportDtos.Row> rows, boolean reports) {
        return rows.stream().map(OpsReportDtos.Row::id).toList();
    }

    private double average(String rateeId) {
        return ratingRepository.summarize(List.of(rateeId)).getFirst().getAvgStars();
    }

    /** Moves created_at to {@code minutes} (and a few seconds) ago, as the application reads it - shifted in SQL, so the column's time zone doesn't matter. */
    private void backdate(SosIncident sos, int minutes) {
        Instant stored = sosRepository.findById(sos.getId()).orElseThrow().getCreatedAt();
        Instant target = Instant.now().minus(minutes, ChronoUnit.MINUTES).minusSeconds(5);
        jdbc.update("update trip.sos_incidents set created_at = created_at + make_interval(secs => ?) where id = ?",
                (double) java.time.Duration.between(stored, target).toMillis() / 1000.0, sos.getId());
    }

    private RoutePost route(String driverId) {
        return routeRepository.save(RoutePost.builder()
                .driverId(driverId).vehicleId("vehicle-1")
                .originLabel("Ikorodu").originLat(6.62).originLng(3.51)
                .destinationLabel("Victoria Island").destinationLat(6.43).destinationLng(3.42)
                .departureTime(Instant.now().plus(1, ChronoUnit.HOURS))
                .seatCapacity(4).seatsAvailable(4).farePerSeat(new BigDecimal("1200.00"))
                .status(RouteStatus.IN_PROGRESS)
                .build());
    }

    private Booking seat(RoutePost route, String riderId) {
        return bookingRepository.save(Booking.builder()
                .routePostId(route.getId()).riderId(riderId).seatCount(1).fareTotal(new BigDecimal("1200.00"))
                .status(BookingStatus.IN_PROGRESS).holdExpiresAt(Instant.now()).confirmedAt(Instant.now())
                .build());
    }

    private SosIncident sos(RoutePost route, Booking booking, String by, String role) {
        return sosRepository.save(SosIncident.builder()
                .routePostId(route.getId()).bookingId(booking == null ? null : booking.getId())
                .triggeredBy(by).triggeredByRole(role).latitude(6.5).longitude(3.4).opsNotifiedAt(Instant.now())
                .contactsNotified(OpsSosService.contactsJson(List.of(new OpsSosDtos.Contact("Mum", "08011110000"))))
                .build());
    }

    private IssueReport report(Booking booking, String by, String role, IssueCategory category, String status) {
        return reportRepository.save(IssueReport.builder()
                .bookingId(booking == null ? null : booking.getId()).reportedBy(by).reporterRole(role).category(category)
                .description("Something happened").referenceNumber("ISS-" + Math.abs(System.nanoTime() % 100_000_000))
                .status(status).build());
    }

    private Rating rating(Booking booking, String rater, String ratee, int stars, String comment) {
        return ratingRepository.save(Rating.builder().bookingId(booking.getId()).raterId(rater).rateeId(ratee)
                .direction(RatingDirection.RIDER_TO_DRIVER).stars(stars).comment(comment).build());
    }
}

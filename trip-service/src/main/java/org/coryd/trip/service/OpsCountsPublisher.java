package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.SosStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsCountsChangedEvent;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Ops Console's live badges - SOS by status, stuck trips, open reports - sent to ops-service's stream whenever they
 * change. Checked every 30 seconds (a trip turns stuck with no event of its own), straight after any SOS or report change
 * commits, and sent at least every 5 minutes so a console that connects late still gets a recent figure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsCountsPublisher {

    /** Published (as a Spring event) by anything that changes a badge - an SOS raised or handled, a report handled. */
    public record Dirty() {
    }

    private static final Duration RESEND_AT_LEAST_EVERY = Duration.ofMinutes(5);

    private final SosIncidentRepository sosIncidentRepository;
    private final IssueReportRepository issueReportRepository;
    private final RoutePostRepository routePostRepository;
    private final StuckTripProperties stuckTripProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private final AtomicReference<OpsCountsChangedEvent> lastSent = new AtomicReference<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDirty(Dirty dirty) {
        publishIfChanged(false);
    }

    @Scheduled(fixedDelayString = "${trip.ops-counts.check-ms:30000}", initialDelayString = "${trip.ops-counts.check-ms:30000}")
    public void tick() {
        publishIfChanged(false);
    }

    /** Best effort - a badge a few seconds stale is better than an action that fails. */
    public OpsCountsChangedEvent publishIfChanged(boolean force) {
        try {
            OpsCountsChangedEvent now = compute();
            OpsCountsChangedEvent previous = lastSent.get();
            boolean due = previous == null || previous.getComputedAt().plus(RESEND_AT_LEAST_EVERY).isBefore(now.getComputedAt());
            if (force || due || !sameCounts(previous, now)) {
                kafkaTemplate.send(KafkaTopics.OPS_COUNTS_CHANGED, "ops-counts", now);
                lastSent.set(now);
            }
            return now;
        } catch (Exception e) {
            log.warn("Could not send ops counts: {}", e.getMessage());
            return null;
        }
    }

    OpsCountsChangedEvent compute() {
        Instant now = Instant.now();
        long stuck = routePostRepository.findByStatus(RouteStatus.IN_PROGRESS).stream()
                .filter(r -> stuckTripProperties.isStuck(r, now)).count();
        return OpsCountsChangedEvent.builder()
                .sosOpen(sosIncidentRepository.countByStatus(SosStatus.OPEN))
                .sosAcknowledged(sosIncidentRepository.countByStatus(SosStatus.ACKNOWLEDGED))
                .sosEscalated(sosIncidentRepository.countByStatus(SosStatus.ESCALATED))
                .stuckTrips(stuck)
                .reportsOpen(issueReportRepository.countByStatus(IssueReportStatus.OPEN.name()))
                .reportsInProgress(issueReportRepository.countByStatus(IssueReportStatus.IN_PROGRESS.name()))
                .computedAt(now)
                .build();
    }

    private static boolean sameCounts(OpsCountsChangedEvent a, OpsCountsChangedEvent b) {
        return a.getSosOpen() == b.getSosOpen() && a.getSosAcknowledged() == b.getSosAcknowledged()
                && a.getSosEscalated() == b.getSosEscalated() && a.getStuckTrips() == b.getStuckTrips()
                && a.getReportsOpen() == b.getReportsOpen() && a.getReportsInProgress() == b.getReportsInProgress();
    }
}

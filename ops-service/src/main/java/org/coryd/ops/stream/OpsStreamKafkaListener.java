package org.coryd.ops.stream;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsCountsChangedEvent;
import org.coryd.common.kafka.events.OpsSosChangedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripSafetyEscalatedEvent;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the platform's own Kafka events into the console's stream events. Every ops-service instance reads every event
 * (its own consumer group - see OpsStreamKafkaConfig), so a console on any instance hears everything.
 */
@Component
@RequiredArgsConstructor
@KafkaListener(topics = {KafkaTopics.SOS_TRIGGERED, KafkaTopics.OPS_SOS_CHANGED, KafkaTopics.TRIP_SAFETY_ESCALATED,
        KafkaTopics.OPS_COUNTS_CHANGED}, containerFactory = OpsStreamKafkaConfig.FACTORY)
public class OpsStreamKafkaListener {

    public static final String SOS_RAISED = "sos.raised";
    public static final String SOS_UPDATED = "sos.updated";
    public static final String SAFETY_ESCALATED = "safety.escalated";

    private final OpsEventStream stream;

    @KafkaHandler
    public void sosTriggered(SosTriggeredEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", e.getIncidentId());
        data.put("kind", "SOS");
        data.put("status", "OPEN");
        data.put("triggeredByRole", e.getTriggeredByRole());
        data.put("bookingId", e.getBookingId());
        data.put("routePostId", e.getRoutePostId());
        data.put("routeSummary", e.getRouteSummary());
        data.put("driverName", e.getDriverName());
        data.put("driverPhone", e.getDriverPhone());
        data.put("riderPhone", e.getRiderPhone());
        data.put("vehiclePlate", e.getVehiclePlate());
        data.put("latitude", e.getLatitude());
        data.put("longitude", e.getLongitude());
        data.put("raisedAt", e.getTriggeredAt() == null ? Instant.now() : e.getTriggeredAt().atZone(ZoneId.systemDefault()).toInstant());
        stream.publish(SOS_RAISED, data);
    }

    @KafkaHandler
    public void sosChanged(OpsSosChangedEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", e.getIncidentId());
        data.put("kind", e.getKind());
        data.put("action", e.getAction());
        data.put("status", e.getStatus());
        data.put("routePostId", e.getRoutePostId());
        data.put("bookingId", e.getBookingId());
        data.put("safetyAlertId", e.getSafetyAlertId());
        data.put("actorId", e.getActorId());
        data.put("actorName", e.getActorName());
        data.put("outcome", e.getOutcome());
        data.put("minutesOpen", e.getMinutesOpen());
        data.put("at", e.getAt());
        stream.publish("RAISED".equals(e.getAction()) ? SOS_RAISED : SOS_UPDATED, data);
    }

    @KafkaHandler
    public void safetyEscalated(TripSafetyEscalatedEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alertId", e.getAlertId());
        data.put("routePostId", e.getRoutePostId());
        data.put("kind", e.getKind());
        data.put("routeSummary", e.getRouteSummary());
        data.put("driverName", e.getDriverName());
        data.put("driverPhone", e.getDriverPhone());
        data.put("vehiclePlate", e.getVehiclePlate());
        data.put("riderPhones", e.getRiderPhones());
        data.put("latitude", e.getLatitude());
        data.put("longitude", e.getLongitude());
        data.put("unanswered", e.getUnanswered());
        data.put("notAnswered", e.getNotAnswered());
        data.put("answeredOk", e.getAnsweredOk());
        stream.publish(SAFETY_ESCALATED, data);
    }

    @KafkaHandler
    public void counts(OpsCountsChangedEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sos", Map.of("open", e.getSosOpen(), "acknowledged", e.getSosAcknowledged(), "escalated", e.getSosEscalated()));
        data.put("stuckTrips", e.getStuckTrips());
        data.put("reports", Map.of("open", e.getReportsOpen(), "inProgress", e.getReportsInProgress()));
        data.put("computedAt", e.getComputedAt());
        stream.publish(OpsEventStream.COUNTS, data);
    }

    @KafkaHandler(isDefault = true)
    public void other(Object ignored) {
        // an event type this stream doesn't show
    }
}

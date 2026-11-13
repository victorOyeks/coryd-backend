package org.coryd.trip.event;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.TripSafetyEscalatedEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.service.OpsSosService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * An "Are you OK?" check nobody answered joins the Ops Console's SOS queue as a SAFETY_CHECK incident. Fed by the same
 * event that alerts ops by SMS / WhatsApp / email, so TripSafetyService itself is unchanged; once per check.
 */
@Component
@RequiredArgsConstructor
public class SafetyEscalationEventListener {

    private final OpsSosService opsSosService;

    @KafkaListener(topics = KafkaTopics.TRIP_SAFETY_ESCALATED, groupId = KafkaConfig.GROUP_ID)
    public void onEscalated(TripSafetyEscalatedEvent event) {
        opsSosService.openForSafetyCheck(event.getAlertId());
    }
}

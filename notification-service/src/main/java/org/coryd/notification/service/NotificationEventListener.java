package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.common.kafka.events.TripShareRequestedEvent;
import org.coryd.notification.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationDispatchService dispatchService;

    @KafkaListener(topics = KafkaTopics.OTP_ISSUED, groupId = KafkaConfig.GROUP_ID)
    public void onOtpIssued(OtpIssuedEvent event) {
        dispatchService.sendOtp(event.getPhone(), "Your CoRyd verification code is " + event.getCode(),
                "OTP_ISSUED", event.getChannel());
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CONFIRMED, groupId = KafkaConfig.GROUP_ID)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Booking confirmed",
                "Your seat is confirmed. See you at pickup!", "BOOKING_CONFIRMED");
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = KafkaConfig.GROUP_ID)
    public void onBookingCancelled(BookingCancelledEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Booking cancelled",
                "Your booking was cancelled by " + event.getCancelledBy().toLowerCase() + ".", "BOOKING_CANCELLED");
    }

    @KafkaListener(topics = KafkaTopics.ROUTE_CANCELLED, groupId = KafkaConfig.GROUP_ID)
    public void onRouteCancelled(RouteCancelledEvent event) {
        // Per-rider notification for R-704 is handled by the booking-cancelled listener above,
        // which trip-service publishes once per affected booking. This just confirms to the driver.
        dispatchService.sendPush(event.getDriverId(), "Route cancelled",
                "Your route post was cancelled and " + event.getAffectedBookingIds().size() + " booking(s) were refunded.",
                "ROUTE_CANCELLED");
    }

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID)
    public void onTripCompleted(TripCompletedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Trip completed",
                "Your trip is complete. Rate your driver!", "TRIP_COMPLETED");
    }

    @KafkaListener(topics = KafkaTopics.NOTIFY_ME_MATCHED, groupId = KafkaConfig.GROUP_ID)
    public void onNotifyMeMatched(NotifyMeMatchedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "A ride is available",
                "A driver just posted a route matching your search.", "NOTIFY_ME_MATCHED");
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_EXPIRING, groupId = KafkaConfig.GROUP_ID)
    public void onDocumentExpiring(DocumentExpiringEvent event) {
        dispatchService.sendPush(event.getDriverId(), "Document expiring soon",
                "Your " + event.getDocumentType() + " expires in " + event.getDaysRemaining() + " days.", "DOCUMENT_EXPIRING");
    }

    @KafkaListener(topics = KafkaTopics.TRIP_SHARE_REQUESTED, groupId = KafkaConfig.GROUP_ID)
    public void onTripShareRequested(TripShareRequestedEvent event) {
        dispatchService.sendSms(event.getContactPhone(),
                (event.getContactName() == null ? "Hi" : "Hi " + event.getContactName()) +
                        ", someone you know shared their CoRyd trip with you - track it here: " + event.getShareLink(),
                "TRIP_SHARE_REQUESTED");
    }

    @KafkaListener(topics = KafkaTopics.SOS_TRIGGERED, groupId = KafkaConfig.GROUP_ID)
    public void onSosTriggered(SosTriggeredEvent event) {
        if (event.getEmergencyContactPhone() == null || event.getEmergencyContactPhone().isBlank()) {
            log.warn("SOS triggered for booking {} but rider has no emergency contact set - no SMS sent", event.getBookingId());
        } else {
            String locationText = event.getLatitude() == null ? ""
                    : " Last known location: " + event.getLatitude() + "," + event.getLongitude() + ".";
            dispatchService.sendSms(event.getEmergencyContactPhone(),
                    (event.getEmergencyContactName() == null ? "Hi" : "Hi " + event.getEmergencyContactName()) +
                            ", this is an emergency alert from CoRyd - someone you know triggered SOS during their trip." + locationText,
                    "SOS_TRIGGERED");
        }

        // Confirmation push to whoever triggered it - matches the app's own "will be shared
        // immediately" promise with an acknowledgement, not just silence after tapping the button.
        dispatchService.sendPush(event.getTriggeredBy(), "Emergency alert sent",
                "Your emergency contact has been notified.", "SOS_TRIGGERED");
    }
}

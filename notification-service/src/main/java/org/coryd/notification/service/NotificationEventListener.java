package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverArrivedEvent;
import org.coryd.common.kafka.events.DriverEnRouteEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.common.kafka.events.EmergencyContactPayload;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.SeatReservedEvent;
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
        String message = event.getPurpose() == OtpPurpose.PASSWORD_RESET
                ? "Your CoRyd password reset code is " + event.getCode() + ". If you didn't request this, ignore this message."
                : "Your CoRyd verification code is " + event.getCode();
        dispatchService.sendOtp(event.getPhone(), message, "OTP_ISSUED", event.getChannel());
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CONFIRMED, groupId = KafkaConfig.GROUP_ID)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Booking confirmed",
                "Your seat is confirmed. See you at pickup!", "BOOKING_CONFIRMED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = KafkaConfig.GROUP_ID)
    public void onBookingCancelled(BookingCancelledEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Booking cancelled",
                "Your booking was cancelled by " + event.getCancelledBy().toLowerCase() + ".", "BOOKING_CANCELLED",
                event.getBookingId());

        // Only tell the driver when the RIDER was the one who cancelled - a seat just freed up on
        // their route and they'd otherwise have no way to know. A driver- or system-initiated
        // cancellation already means the driver knows (they did it, or ROUTE_CANCELLED told them).
        if ("RIDER".equals(event.getCancelledBy()) && event.getDriverId() != null) {
            dispatchService.sendPush(event.getDriverId(), "A seat opened up",
                    "A rider cancelled their booking on your route - the seat is available again.",
                    "BOOKING_CANCELLED", event.getBookingId());
        }
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = KafkaConfig.GROUP_ID)
    public void onPaymentFailed(PaymentFailedEvent event) {
        if (event.getRiderId() == null) {
            return;
        }
        dispatchService.sendPush(event.getRiderId(), "Payment failed",
                "Your payment couldn't be completed" + (event.getReason() == null ? "." : " - " + event.getReason() + ".")
                        + " Your seat hold may have expired - please try booking again.",
                "PAYMENT_FAILED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.REFUND_ISSUED, groupId = KafkaConfig.GROUP_ID)
    public void onRefundIssued(RefundIssuedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Refund issued",
                "You've been refunded ₦" + event.getAmount().toPlainString() + " for a cancelled booking.",
                "REFUND_ISSUED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_EN_ROUTE, groupId = KafkaConfig.GROUP_ID)
    public void onDriverEnRoute(DriverEnRouteEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Driver is on the way",
                (event.getDriverName() == null ? "Your driver" : event.getDriverName()) + " is on the way to pick you up.",
                "DRIVER_EN_ROUTE", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_ARRIVED, groupId = KafkaConfig.GROUP_ID)
    public void onDriverArrived(DriverArrivedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Driver has arrived",
                (event.getDriverName() == null ? "Your driver" : event.getDriverName()) + " has arrived at your pickup point.",
                "DRIVER_ARRIVED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.ROUTE_CANCELLED, groupId = KafkaConfig.GROUP_ID)
    public void onRouteCancelled(RouteCancelledEvent event) {
        // Per-rider notification for R-704 is handled by the booking-cancelled listener above,
        // which trip-service publishes once per affected booking. This just confirms to the driver.
        dispatchService.sendPush(event.getDriverId(), "Route cancelled",
                "Your route post was cancelled and " + event.getAffectedBookingIds().size() + " booking(s) were refunded.",
                "ROUTE_CANCELLED", null);
    }

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID)
    public void onTripCompleted(TripCompletedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Trip completed",
                "Your trip is complete. Rate your driver!", "TRIP_COMPLETED", event.getBookingId());

        // fareTotal here is gross (pre-commission) - it's what the rider paid, not the driver's net
        // credit, but it's the only figure this event carries; still a reasonable "trip closed out"
        // confirmation without payment-service's EarningsTransaction data on hand.
        if (event.getDriverId() != null) {
            dispatchService.sendPush(event.getDriverId(), "Trip completed",
                    event.getFareTotal() == null
                            ? "A trip on your route is complete."
                            : "A trip on your route is complete - ₦" + event.getFareTotal().toPlainString() + " collected.",
                    "TRIP_COMPLETED", event.getBookingId());
        }
    }

    @KafkaListener(topics = KafkaTopics.NOTIFY_ME_MATCHED, groupId = KafkaConfig.GROUP_ID)
    public void onNotifyMeMatched(NotifyMeMatchedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "A ride is available",
                "A driver just posted a route matching your search.", "NOTIFY_ME_MATCHED", null);
    }

    @KafkaListener(topics = KafkaTopics.SEAT_RESERVED, groupId = KafkaConfig.GROUP_ID)
    public void onSeatReserved(SeatReservedEvent event) {
        String driverName = event.getDriverName() == null ? "Your favourite driver" : event.getDriverName();
        dispatchService.sendPush(event.getRiderId(), "Your favourite driver reserved you a seat",
                driverName + " just posted a new trip and held " + event.getSeatCount()
                        + " seat(s) for you - accept within 15 minutes before it's released.",
                "SEAT_RESERVED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_EXPIRING, groupId = KafkaConfig.GROUP_ID)
    public void onDocumentExpiring(DocumentExpiringEvent event) {
        dispatchService.sendPush(event.getDriverId(), "Document expiring soon",
                "Your " + event.getDocumentType() + " expires in " + event.getDaysRemaining() + " days.", "DOCUMENT_EXPIRING", null);
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_APPROVED, groupId = KafkaConfig.GROUP_ID)
    public void onDriverApproved(DriverApprovedEvent event) {
        dispatchService.sendPush(event.getDriverId(), "You're verified!",
                "Your CoRyd driver verification is complete. You can start posting routes now.", "DRIVER_APPROVED", null);
        if (event.getEmail() != null) {
            dispatchService.sendEmail(event.getEmail(), "You're verified on CoRyd",
                    "Good news - your CoRyd driver verification is complete. You can start posting routes now.",
                    "DRIVER_APPROVED");
        }
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_REJECTED, groupId = KafkaConfig.GROUP_ID)
    public void onDriverRejected(DriverRejectedEvent event) {
        String reason = event.getRejectionReason() == null ? "Please review and resubmit." : event.getRejectionReason();
        dispatchService.sendPush(event.getDriverId(), "Action needed on your verification",
                reason, "DRIVER_REJECTED", null);
        if (event.getEmail() != null) {
            dispatchService.sendEmail(event.getEmail(), "Action needed on your CoRyd verification",
                    "One of your verification items needs attention: " + reason, "DRIVER_REJECTED");
        }
    }

    @KafkaListener(topics = KafkaTopics.TRIP_SHARE_REQUESTED, groupId = KafkaConfig.GROUP_ID)
    public void onTripShareRequested(TripShareRequestedEvent event) {
        for (EmergencyContactPayload contact : event.getContacts()) {
            dispatchService.sendSms(contact.getPhone(),
                    (contact.getName() == null ? "Hi" : "Hi " + contact.getName()) +
                            ", someone you know shared their CoRyd trip with you - track it here: " + event.getShareLink(),
                    "TRIP_SHARE_REQUESTED");
        }
    }

    @KafkaListener(topics = KafkaTopics.SOS_TRIGGERED, groupId = KafkaConfig.GROUP_ID)
    public void onSosTriggered(SosTriggeredEvent event) {
        if (event.getContacts() == null || event.getContacts().isEmpty()) {
            log.warn("SOS triggered for booking {} but rider has no emergency contacts set - no SMS sent", event.getBookingId());
        } else {
            String locationText = event.getLatitude() == null ? ""
                    : " Last known location: " + event.getLatitude() + "," + event.getLongitude() + ".";
            for (EmergencyContactPayload contact : event.getContacts()) {
                dispatchService.sendSms(contact.getPhone(),
                        (contact.getName() == null ? "Hi" : "Hi " + contact.getName()) +
                                ", this is an emergency alert from CoRyd - someone you know triggered SOS during their trip." + locationText,
                        "SOS_TRIGGERED");
            }
        }

        // Confirmation push to whoever triggered it, once - regardless of how many contacts were
        // just alerted - matches the app's own "will be shared immediately" promise with a single
        // acknowledgement, not silence (or a flood) after tapping the button.
        dispatchService.sendPush(event.getTriggeredBy(), "Emergency alert sent",
                "Your emergency contact has been notified.", "SOS_TRIGGERED", event.getBookingId());
    }
}

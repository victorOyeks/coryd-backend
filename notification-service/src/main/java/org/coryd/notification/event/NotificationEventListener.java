package org.coryd.notification.event;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverArrivedEvent;
import org.coryd.common.kafka.events.DriverEnRouteEvent;
import org.coryd.common.kafka.events.DriverOwedChangedEvent;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.common.kafka.events.EmergencyContactPayload;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.RouteDepartureReminderEvent;
import org.coryd.common.kafka.events.SeatReservedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.common.kafka.events.TripShareRequestedEvent;
import org.coryd.notification.config.KafkaConfig;
import org.coryd.notification.service.NotificationDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationDispatchService dispatchService;

    @KafkaListener(topics = KafkaTopics.OTP_ISSUED, groupId = KafkaConfig.GROUP_ID_OTP)
    public void onOtpIssued(OtpIssuedEvent event) {
        // Must match Termii's approved message format exactly (business name, expiry, no-share
        // warning) or the DND route can block/flag it - see the "Your CoRyd verification code is
        // 002929. It expires in 5 minutes. Do not share this code with anyone." sample they approved.
        String message = event.getPurpose() == OtpPurpose.PASSWORD_RESET
                ? "Your CoRyd password reset code is " + event.getCode() + ". It expires in 5 minutes. Do not share this code with anyone."
                : "Your CoRyd verification code is " + event.getCode() + ". It expires in 5 minutes. Do not share this code with anyone.";
        dispatchService.sendOtp(event.getPhone(), message, event.getCode(), "OTP_ISSUED", event.getChannel());
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CONFIRMED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        String body = event.getPickupCode() == null
                ? "Your seat is confirmed. See you at pickup!"
                : "Your seat is confirmed. Your pickup code is " + event.getPickupCode() + " - give it to your driver when you board.";
        dispatchService.sendPush(event.getRiderId(), "Booking confirmed", body, "BOOKING_CONFIRMED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onBookingCancelled(BookingCancelledEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Booking cancelled", cancellationCopy(event.getCancelledBy(), event.isPaid()),
                "BOOKING_CANCELLED", event.getBookingId());

        // Only tell the driver when the RIDER was the one who cancelled - a seat just freed up on
        // their route and they'd otherwise have no way to know. A driver- or system-initiated
        // cancellation already means the driver knows (they did it, or ROUTE_CANCELLED told them).
        if ("RIDER".equals(event.getCancelledBy()) && event.getDriverId() != null) {
            dispatchService.sendPush(event.getDriverId(), "A seat opened up",
                    "A rider cancelled their booking on your route - the seat is available again.",
                    "BOOKING_CANCELLED", event.getBookingId());
        }
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onPaymentFailed(PaymentFailedEvent event) {
        if (event.getRiderId() == null) {
            return;
        }
        dispatchService.sendPush(event.getRiderId(), "Payment failed",
                "Your payment couldn't be completed" + (event.getReason() == null ? "." : " - " + event.getReason() + ".")
                        + " Your seat hold may have expired - please try booking again.",
                "PAYMENT_FAILED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.REFUND_ISSUED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onRefundIssued(RefundIssuedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Refund issued",
                "You've been refunded ₦" + event.getAmount().toPlainString() + " for a cancelled booking.",
                "REFUND_ISSUED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_OWED_CHANGED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onDriverOwedChanged(DriverOwedChangedEvent event) {
        dispatchService.sendPush(event.getDriverId(), owedTitle(event), owedCopy(event), "DRIVER_OWED_CHANGED", event.getBookingId());
    }

    static String owedTitle(DriverOwedChangedEvent event) {
        return "REFUNDED_TRIP".equals(event.getReason()) ? "A trip you were paid for was refunded" : "Payment received";
    }

    /** Always says what's owed now, and - if posting is blocked - the least to pay to post again. */
    static String owedCopy(DriverOwedChangedEvent event) {
        String owed = "₦" + event.getAmountOwed().toPlainString();
        String blocked = event.isOverLimit()
                ? " Pay at least ₦" + event.getAmountOwed().subtract(event.getOwedLimit()).toPlainString() + " in the app to post trips again."
                : "";
        if ("REFUNDED_TRIP".equals(event.getReason())) {
            return "₦" + event.getChange().toPlainString() + " will be taken from your next earnings. You now owe " + owed + "." + blocked;
        }
        String paid = "Thanks - you've paid ₦" + event.getChange().negate().toPlainString() + ".";
        return event.getAmountOwed().signum() == 0 ? paid + " You don't owe anything now." : paid + " You still owe " + owed + "." + blocked;
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_EN_ROUTE, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onDriverEnRoute(DriverEnRouteEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Driver is on the way",
                (event.getDriverName() == null ? "Your driver" : event.getDriverName()) + " is on the way to pick you up.",
                "DRIVER_EN_ROUTE", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_ARRIVED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onDriverArrived(DriverArrivedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "Driver has arrived",
                (event.getDriverName() == null ? "Your driver" : event.getDriverName()) + " has arrived at your pickup point.",
                "DRIVER_ARRIVED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.ROUTE_CANCELLED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onRouteCancelled(RouteCancelledEvent event) {
        // Per-rider notification for R-704 is handled by the booking-cancelled listener above,
        // which trip-service publishes once per affected booking. This just confirms to the driver.
        int refunded = event.getAffectedBookingIds() == null ? 0 : event.getAffectedBookingIds().size();
        String cancelled = "NOT_STARTED".equals(event.getCancelledBy())
                ? "Your route wasn't started within 45 minutes of departure, so it was cancelled"
                : "Your route was cancelled";
        String body = refunded == 0 ? cancelled + "."
                : cancelled + " and " + count(refunded, "booking", "bookings") + (refunded == 1 ? " was" : " were") + " refunded.";
        dispatchService.sendRoutePush(event.getDriverId(), "Route cancelled", body, "ROUTE_CANCELLED", event.getRoutePostId());
    }

    /** Driver reminders deep-link by routePostId (manifest), rider reminders by bookingId (trip details). */
    @KafkaListener(topics = KafkaTopics.ROUTE_DEPARTURE_REMINDER, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onRouteDepartureReminder(RouteDepartureReminderEvent event) {
        String routeSummary = event.getOriginLabel() + " -> " + event.getDestinationLabel();
        int riderCount = event.getRiders() == null ? 0 : event.getRiders().size();
        String kind = event.getKind() == null ? "RIDER_GET_READY" : event.getKind();

        switch (kind) {
            case "DRIVER_GET_READY" -> dispatchService.sendRoutePush(event.getDriverId(), "Get ready to depart",
                    "Your route " + routeSummary + " departs " + inMinutes(event.getDepartureTime()) + " - "
                            + (riderCount == 0 ? "no riders booked yet." : count(riderCount, "rider", "riders") + " booked."),
                    "DRIVER_DEPARTURE_REMINDER", event.getRoutePostId());
            case "DRIVER_START_OVERDUE" -> dispatchService.sendRoutePush(event.getDriverId(), "Your trip is due to leave",
                    "Start your route " + routeSummary + " or cancel it - it's cancelled automatically 45 minutes after departure"
                            + (riderCount == 0 ? "." : " and " + count(riderCount, "rider", "riders") + " will be refunded."),
                    "ROUTE_START_OVERDUE", event.getRoutePostId());
            default -> {
                if (event.getRiders() == null) {
                    return;
                }
                for (RouteDepartureReminderEvent.RiderBooking rider : event.getRiders()) {
                    dispatchService.sendPush(rider.getRiderId(), "Almost time for your ride",
                            "Your ride " + routeSummary + " departs " + inMinutes(event.getDepartureTime())
                                    + " - be at your pickup point with your pickup code ready.",
                            "ROUTE_DEPARTURE_REMINDER", rider.getBookingId());
                }
            }
        }
    }

    private static String inMinutes(Instant departure) {
        if (departure == null) {
            return "soon";
        }
        long minutes = Math.max(1, Duration.between(Instant.now(), departure).toMinutes());
        return "in " + minutes + " minute" + (minutes == 1 ? "" : "s");
    }

    /** The refund line only for riders who paid - an unpaid hold or reservation had nothing to refund. */
    static String cancellationCopy(String cancelledBy, boolean paid) {
        String refund = paid ? " You've been fully refunded." : "";
        if (cancelledBy == null) {
            return "Your booking was cancelled.";
        }
        return switch (cancelledBy) {
            case "NOT_STARTED" -> "Your driver didn't start this trip." + refund;
            case "DRIVER" -> "Your driver cancelled this trip." + refund;
            case "RIDER" -> "Your booking was cancelled.";
            default -> "Your booking couldn't go ahead and has been cancelled." + (paid ? " Your payment has been refunded." : "");
        };
    }

    /** "1 rider", "3 riders" - never "rider(s)". */
    static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID_TRIP)
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

    @KafkaListener(topics = KafkaTopics.NOTIFY_ME_MATCHED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onNotifyMeMatched(NotifyMeMatchedEvent event) {
        dispatchService.sendPush(event.getRiderId(), "A ride is available",
                "A driver just posted a route matching your search.", "NOTIFY_ME_MATCHED", null);
    }

    @KafkaListener(topics = KafkaTopics.SEAT_RESERVED, groupId = KafkaConfig.GROUP_ID_TRIP)
    public void onSeatReserved(SeatReservedEvent event) {
        String driverName = event.getDriverName() == null ? "Your favourite driver" : event.getDriverName();
        dispatchService.sendPush(event.getRiderId(), "Your favourite driver reserved you a seat",
                driverName + " just posted a new trip and held " + count(event.getSeatCount(), "seat", "seats")
                        + " for you - accept within 15 minutes before it's released.",
                "SEAT_RESERVED", event.getBookingId());
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_EXPIRING, groupId = KafkaConfig.GROUP_ID_ADMIN)
    public void onDocumentExpiring(DocumentExpiringEvent event) {
        dispatchService.sendPush(event.getDriverId(), "Document expiring soon",
                "Your " + event.getDocumentType() + " expires in " + event.getDaysRemaining() + " days.", "DOCUMENT_EXPIRING", null);
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_APPROVED, groupId = KafkaConfig.GROUP_ID_ADMIN)
    public void onDriverApproved(DriverApprovedEvent event) {
        dispatchService.sendPush(event.getDriverId(), "You're verified!",
                "Your CoRyd driver verification is complete. You can start posting routes now.", "DRIVER_APPROVED", null);
        if (event.getEmail() != null) {
            dispatchService.sendEmail(event.getEmail(), "You're verified on CoRyd",
                    "Good news - your CoRyd driver verification is complete. You can start posting routes now.",
                    "DRIVER_APPROVED");
        }
    }

    /** Fires per decided item, so the driver hears about each document rather than only about the
     * milestones the aggregate events mark. DRIVER_REJECTED deliberately notifies nothing now - it
     * would arrive alongside this one saying less. */
    @KafkaListener(topics = KafkaTopics.VERIFICATION_ITEM_DECIDED, groupId = KafkaConfig.GROUP_ID_ADMIN)
    public void onVerificationItemDecided(VerificationItemDecidedEvent event) {
        String label = event.getItemLabel();
        String title = event.isApproved() ? label + " approved" : label + " needs attention";
        String body = event.isApproved()
                ? "Your " + label.toLowerCase() + " has been approved."
                : (event.getRejectionReason() == null
                        ? "Your " + label.toLowerCase() + " was not accepted. Please review and resubmit."
                        : event.getRejectionReason());
        String type = event.isApproved() ? "VERIFICATION_ITEM_APPROVED" : "VERIFICATION_ITEM_REJECTED";

        dispatchService.sendPush(event.getDriverId(), title, body, type, null);
        if (event.getEmail() != null) {
            dispatchService.sendEmail(event.getEmail(), title + " - CoRyd", body, type);
        }
    }

    @KafkaListener(topics = KafkaTopics.TRIP_SHARE_REQUESTED, groupId = KafkaConfig.GROUP_ID_SAFETY)
    public void onTripShareRequested(TripShareRequestedEvent event) {
        for (EmergencyContactPayload contact : event.getContacts()) {
            dispatchService.sendSms(contact.getPhone(),
                    (contact.getName() == null ? "Hi" : "Hi " + contact.getName()) +
                            ", someone you know shared their CoRyd trip with you - track it here: " + event.getShareLink(),
                    "TRIP_SHARE_REQUESTED");
        }
    }

    @KafkaListener(topics = KafkaTopics.SOS_TRIGGERED, groupId = KafkaConfig.GROUP_ID_SAFETY)
    public void onSosTriggered(SosTriggeredEvent event) {
        boolean anyContact = event.getContacts() != null && !event.getContacts().isEmpty();
        if (!anyContact) {
            log.warn("SOS triggered for booking {} by {} with no emergency contacts set - no SMS sent", event.getBookingId(), event.getTriggeredBy());
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
        // Only claims a contact was alerted when one actually was.
        dispatchService.sendPush(event.getTriggeredBy(), anyContact ? "Emergency alert sent" : "No emergency contact set",
                anyContact ? "Your emergency contact has been notified by SMS."
                        : "We couldn't alert anyone - add an emergency contact in your profile.",
                "SOS_TRIGGERED", event.getBookingId());
    }
}

package org.coryd.notification.event;

import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.RouteDepartureReminderEvent;
import org.coryd.common.kafka.events.SeatReservedEvent;
import org.coryd.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The wording drivers and riders actually see - singular/plural, and refunds only promised when there was a payment. */
class NotificationCopyTest {

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final org.coryd.notification.config.SosAlertProperties sos = new org.coryd.notification.config.SosAlertProperties();
    private final NotificationEventListener listener = new NotificationEventListener(dispatch, sos);

    @Test
    void aWaitingRiderIsToldWhereAndWhenAndThePushOpensTheRide() {
        listener.onNotifyMeMatched(org.coryd.common.kafka.events.NotifyMeMatchedEvent.builder()
                .riderId("rider-1").routePostId("route-9").originLabel("Ikorodu").destinationLabel("Victoria Island")
                .departureTime(Instant.parse("2026-10-13T06:00:00Z")).build());

        verify(dispatch).sendRoutePush(eq("rider-1"), eq("A ride is available"),
                eq("A driver is heading from Ikorodu to Victoria Island on Tue 13 Oct at 7:00 AM. Book your seat."),
                eq("NOTIFY_ME_MATCHED"), eq("route-9"));
    }

    @Test
    void ridersOnAMovedTripAreToldTheNewAndOldTimeAndOnlyThoseMovedFarEnoughHearCancellingIsFree() {
        listener.onRouteTimeChanged(org.coryd.common.kafka.events.RouteTimeChangedEvent.builder()
                .routePostId("route-9").originLabel("Church X").destinationLabel("Ajah")
                .previousDepartureTime(Instant.parse("2026-10-11T12:30:00Z"))
                .departureTime(Instant.parse("2026-10-11T13:00:00Z"))
                .riders(List.of(
                        org.coryd.common.kafka.events.RouteTimeChangedEvent.RiderBooking.builder().riderId("rider-1").bookingId("b-1").freeCancellation(true).build(),
                        org.coryd.common.kafka.events.RouteTimeChangedEvent.RiderBooking.builder().riderId("rider-2").bookingId("b-2").freeCancellation(false).build()))
                .build());

        verify(dispatch).sendPush(eq("rider-1"), eq("Your trip's time changed"),
                eq("Your ride Church X -> Ajah now leaves Sun 11 Oct at 2:00 PM (was Sun 11 Oct at 1:30 PM). "
                        + "Your seat is kept - if the new time doesn't work, you can cancel free for a full refund."),
                eq("ROUTE_TIME_CHANGED"), eq("b-1"));
        verify(dispatch).sendPush(eq("rider-2"), eq("Your trip's time changed"),
                eq("Your ride Church X -> Ajah now leaves Sun 11 Oct at 2:00 PM (was Sun 11 Oct at 1:30 PM). Your seat is kept."),
                eq("ROUTE_TIME_CHANGED"), eq("b-2"));
    }

    @Test
    void aTimeChangeWithNoRidersSendsNothing() {
        listener.onRouteTimeChanged(org.coryd.common.kafka.events.RouteTimeChangedEvent.builder().routePostId("route-9").build());
        org.mockito.Mockito.verifyNoInteractions(dispatch);
    }

    @Test
    void anSosTextsTheContactsAndEveryOpsPhoneAndEmailsEveryOpsAddress() {
        sos.setOpsPhones(List.of("08011111111", " 08022222222 ", ""));
        sos.setOpsEmails(List.of("ops@coryd.ng", "founder@coryd.ng"));

        listener.onSosTriggered(sosEvent().contacts(List.of(
                org.coryd.common.kafka.events.EmergencyContactPayload.builder().name("Wife").phone("08050000000").build())).build());

        verify(dispatch).sendSms(eq("08050000000"), any(), eq("SOS_TRIGGERED"));
        String sms = "CoRyd SOS: the driver pressed SOS on Eko Hotel → Ikorodu. Rider 08030000000. Driver Tunde 08040000000, "
                + "car LND123AB. https://maps.google.com/?q=6.45,3.39";
        verify(dispatch).sendSms(eq("08011111111"), eq(sms), eq("SOS_OPS_ALERT"));
        verify(dispatch).sendSms(eq("08022222222"), eq(sms), eq("SOS_OPS_ALERT"));
        verify(dispatch, org.mockito.Mockito.times(3)).sendSms(any(), any(), any());
        verify(dispatch).sendEmail(eq("ops@coryd.ng"), eq("SOS: driver on Eko Hotel → Ikorodu"), any(), eq("SOS_OPS_ALERT"));
        verify(dispatch).sendEmail(eq("founder@coryd.ng"), any(), any(), eq("SOS_OPS_ALERT"));
    }

    @Test
    void theOpsEmailSaysWhoToCallAndWhereAndMissingDetailsReadAsUnknown() {
        String body = NotificationEventListener.sosOpsEmail(sosEvent().driverPhone(null).latitude(null).longitude(null).build(),
                Instant.parse("2026-10-15T18:05:00Z"));

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("The driver on this trip pressed SOS at 15 Oct, 7:05 PM (Lagos time).")
                .contains("Rider phone: 08030000000")
                .contains("Driver: Tunde, unknown")
                .contains("Car: LND123AB")
                .contains("Location: not shared")
                .contains("Incident sos-1, booking booking-1");
    }

    @Test
    void withNoOpsConfiguredOnlyTheContactsAreTexted() {
        listener.onSosTriggered(sosEvent().contacts(List.of(
                org.coryd.common.kafka.events.EmergencyContactPayload.builder().name("Wife").phone("08050000000").build())).build());

        verify(dispatch).sendSms(eq("08050000000"), any(), eq("SOS_TRIGGERED"));
        verify(dispatch, org.mockito.Mockito.never()).sendSms(any(), any(), eq("SOS_OPS_ALERT"));
        verify(dispatch, org.mockito.Mockito.never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    void everyoneOnBoardIsAskedIfTheyAreOk() {
        listener.onTripSafetyCheck(org.coryd.common.kafka.events.TripSafetyCheckEvent.builder()
                .alertId("alert-1").kind("STOPPED").userIds(List.of("driver-1", "rider-1")).build());

        String body = "Your car has been stopped for a while away from any pickup. Tap to tell us you're fine, or get help.";
        verify(dispatch).sendPush(eq("driver-1"), eq("Are you OK?"), eq(body), eq("SAFETY_CHECK"), eq("alert-1"));
        verify(dispatch).sendPush(eq("rider-1"), eq("Are you OK?"), eq(body), eq("SAFETY_CHECK"), eq("alert-1"));
    }

    @Test
    void anUnansweredSafetyCheckTextsOpsWhoDidntAnswerByRole() {
        sos.setOpsPhones(List.of("08011111111"));
        listener.onTripSafetyEscalated(org.coryd.common.kafka.events.TripSafetyEscalatedEvent.builder()
                .alertId("alert-1").kind("SIGNAL_LOST").routeSummary("Obalende → Berger").driverName("Kunle Adeyemi").driverPhone("0804")
                .vehiclePlate("KJA001XA").riderPhones("0803, 0805").unanswered(2)
                .notAnswered("driver Kunle Adeyemi 0804, rider 0803").answeredOk("rider 0805").build());

        verify(dispatch).sendSms(eq("08011111111"), eq("CoRyd safety: no reply to an Are-you-OK check. Trip Obalende to Berger: "
                + "the driver's location stopped updating. No reply from: driver Kunle Adeyemi 0804, rider 0803. Said OK: rider 0805. "
                + "Car KJA001XA."), eq("SAFETY_ESCALATED"));
    }

    @Test
    void anOffRouteAlertCarriesTheLastLocationAndNoSmsUnfriendlyArrows() {
        sos.setOpsPhones(List.of("08011111111"));
        listener.onTripSafetyEscalated(org.coryd.common.kafka.events.TripSafetyEscalatedEvent.builder()
                .alertId("alert-1").kind("OFF_ROUTE").routeSummary("Ikorodu → VI").vehiclePlate("LND1").unanswered(1)
                .notAnswered("rider 0803").latitude(6.5).longitude(3.4).build());

        verify(dispatch).sendSms(eq("08011111111"), eq("CoRyd safety: no reply to an Are-you-OK check. Trip Ikorodu to VI: "
                + "the car went far off its route. No reply from: rider 0803. Car LND1. Last seen: https://maps.google.com/?q=6.5,3.4"),
                eq("SAFETY_ESCALATED"));
    }

    @Test
    void aRiderIsToldWhenASeatOpensOnARideThatHadFilled() {
        listener.onNotifyMeMatched(org.coryd.common.kafka.events.NotifyMeMatchedEvent.builder()
                .riderId("rider-1").routePostId("route-9").originLabel("Ikorodu").destinationLabel("Victoria Island")
                .departureTime(Instant.parse("2026-10-13T06:00:00Z")).seatOpened(true).build());

        verify(dispatch).sendRoutePush(eq("rider-1"), eq("A seat just opened"),
                eq("A seat just opened on the Ikorodu → Victoria Island ride on Tue 13 Oct at 7:00 AM. Book it before it goes."),
                eq("NOTIFY_ME_MATCHED"), eq("route-9"));
    }

    @Test
    void aDriverIsToldAboutOneRouteByNameOrSeveralInSum() {
        var one = org.coryd.common.kafka.events.DriverDemandAlertEvent.builder().driverId("driver-1").routes(List.of(
                demandRoute("Ikorodu", "Victoria Island", "06:30", "08:10", 9))).build();
        listener.onDriverDemandAlert(one);
        verify(dispatch).sendPush(eq("driver-1"), eq("Riders are looking for a ride"),
                eq("9 riders want Ikorodu → Victoria Island tomorrow between 6:30 and 8:10 AM. Post a ride."), eq("DRIVER_DEMAND_ALERT"), any());

        var several = org.coryd.common.kafka.events.DriverDemandAlertEvent.builder().driverId("driver-2").routes(List.of(
                demandRoute("Ikorodu", "Victoria Island", "07:00", "08:00", 9), demandRoute("Ikorodu", "Lekki", "17:00", "18:00", 5))).build();
        listener.onDriverDemandAlert(several);
        verify(dispatch).sendPush(eq("driver-2"), any(),
                eq("Riders are looking for 2 routes you drive tomorrow. See them in the app and post a ride."), any(), any());
    }

    @Test
    void timeWindowsReadLikeAClock() {
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.window("07:15", "07:15")).isEqualTo("around 7:15 AM");
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.window("06:30", "08:10")).isEqualTo("between 6:30 and 8:10 AM");
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.window("11:30", "13:00")).isEqualTo("between 11:30 AM and 1:00 PM");
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.window("00:05", "00:05")).isEqualTo("around 12:05 AM");
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.window("17:00", "18:30")).isEqualTo("between 5:00 and 6:30 PM");
    }

    private static org.coryd.common.kafka.events.DriverDemandAlertEvent.DemandRoute demandRoute(String from, String to, String start,
                                                                                                String end, int riders) {
        return org.coryd.common.kafka.events.DriverDemandAlertEvent.DemandRoute.builder()
                .originLabel(from).destinationLabel(to).windowStart(start).windowEnd(end).riderCount(riders).build();
    }

    @Test
    void aDriverIsToldWhatARefundAddsAndHowToPostAgainWhenBlocked() {
        listener.onDriverOwedChanged(owed("REFUNDED_TRIP", "1140.00", "1140.00", false));
        verify(dispatch).sendPush(eq("driver-1"), eq("A trip you were paid for was refunded"),
                eq("₦1140.00 will be taken from your next earnings. You now owe ₦1140.00."), eq("DRIVER_OWED_CHANGED"), eq("booking-1"));

        listener.onDriverOwedChanged(owed("REFUNDED_TRIP", "1140.00", "12500.00", true));
        verify(dispatch).sendPush(eq("driver-1"), any(),
                eq("₦1140.00 will be taken from your next earnings. You now owe ₦12500.00. Pay at least ₦2500.00 in the app to post trips again."),
                any(), any());
    }

    @Test
    void aDriverIsThankedForPayingAndToldWhatIsLeft() {
        listener.onDriverOwedChanged(owed("PAID_BY_CARD", "-1140.00", "0.00", false));
        verify(dispatch).sendPush(eq("driver-1"), eq("Payment received"),
                eq("Thanks - you've paid ₦1140.00. You don't owe anything now."), any(), any());

        listener.onDriverOwedChanged(owed("PAID_BY_CARD", "-500.00", "640.00", false));
        verify(dispatch).sendPush(eq("driver-1"), eq("Payment received"),
                eq("Thanks - you've paid ₦500.00. You still owe ₦640.00."), any(), any());
    }

    private static org.coryd.common.kafka.events.DriverOwedChangedEvent owed(String reason, String change, String owed, boolean overLimit) {
        return org.coryd.common.kafka.events.DriverOwedChangedEvent.builder().driverId("driver-1").reason(reason)
                .change(new java.math.BigDecimal(change)).amountOwed(new java.math.BigDecimal(owed))
                .owedLimit(new java.math.BigDecimal("10000.00")).overLimit(overLimit).bookingId("booking-1").build();
    }

    @Test
    void getReadyReminderCountsRiders() {
        listener.onRouteDepartureReminder(reminder("DRIVER_GET_READY", 0));
        verify(dispatch).sendRoutePush(eq("driver-1"), eq("Get ready to depart"),
                eq("Your route Lekki -> Ikoyi departs in 29 minutes - no riders booked yet."), eq("DRIVER_DEPARTURE_REMINDER"), eq("route-1"));

        listener.onRouteDepartureReminder(reminder("DRIVER_GET_READY", 1));
        verify(dispatch).sendRoutePush(any(), any(),
                eq("Your route Lekki -> Ikoyi departs in 29 minutes - 1 rider booked."), any(), any());

        listener.onRouteDepartureReminder(reminder("DRIVER_GET_READY", 3));
        verify(dispatch).sendRoutePush(any(), any(),
                eq("Your route Lekki -> Ikoyi departs in 29 minutes - 3 riders booked."), any(), any());
    }

    @Test
    void getReadyReminderAddsHowManyNearTheStartAreGoingTheDriversWay() {
        RouteDepartureReminderEvent four = reminder("DRIVER_GET_READY", 1);
        four.setWaitingNearby(4);
        listener.onRouteDepartureReminder(four);
        verify(dispatch).sendRoutePush(any(), any(), eq("Your route Lekki -> Ikoyi departs in 29 minutes - 1 rider booked."
                + " 4 people near the start are going your way."), eq("DRIVER_DEPARTURE_REMINDER"), eq("route-1"));

        RouteDepartureReminderEvent one = reminder("DRIVER_GET_READY", 0);
        one.setWaitingNearby(1);
        listener.onRouteDepartureReminder(one);
        verify(dispatch).sendRoutePush(any(), any(), eq("Your route Lekki -> Ikoyi departs in 29 minutes - no riders booked yet."
                + " 1 person near the start is going your way."), any(), any());

        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.waitingCopy(0)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.waitingCopy(null)).isEmpty();
    }

    @Test
    void aDriverNearWaitingRidersIsToldWhereTheyreGoingAndAskedIfTheyreLeaving() {
        listener.onDriverNearbyDemand(org.coryd.common.kafka.events.DriverNearbyDemandEvent.builder()
                .driverId("driver-1").destinationLabel("Ajah").riderCount(4).build());
        verify(dispatch).sendPush(eq("driver-1"), eq("People near you are going your way"),
                eq("4 people near you are going to Ajah. Leaving now? Share your empty seats."), eq("DRIVER_NEARBY_DEMAND"), eq(null));

        org.assertj.core.api.Assertions.assertThat(NotificationEventListener.nearbyDemandCopy(
                org.coryd.common.kafka.events.DriverNearbyDemandEvent.builder().riderCount(1).destinationLabel(" ").build()))
                .isEqualTo("1 person near you is going your way. Leaving now? Share your empty seats.");
    }

    @Test
    void startNudgeSaysTheTripIsDueToLeave() {
        listener.onRouteDepartureReminder(reminder("DRIVER_START_OVERDUE", 1));
        verify(dispatch).sendRoutePush(eq("driver-1"), eq("Your trip is due to leave"),
                eq("Start your route Lekki -> Ikoyi or cancel it - it's cancelled automatically 45 minutes after departure and 1 rider will be refunded."),
                eq("ROUTE_START_OVERDUE"), eq("route-1"));

        listener.onRouteDepartureReminder(reminder("DRIVER_START_OVERDUE", 0));
        verify(dispatch).sendRoutePush(any(), any(),
                eq("Start your route Lekki -> Ikoyi or cancel it - it's cancelled automatically 45 minutes after departure."), any(), any());
    }

    @Test
    void routeCancelledCountsOnlyRefundedBookings() {
        listener.onRouteCancelled(routeCancelled("DRIVER", 0));
        verify(dispatch).sendRoutePush(eq("driver-1"), eq("Route cancelled"), eq("Your route was cancelled."), eq("ROUTE_CANCELLED"), eq("route-1"));

        listener.onRouteCancelled(routeCancelled("DRIVER", 1));
        verify(dispatch).sendRoutePush(any(), any(), eq("Your route was cancelled and 1 booking was refunded."), any(), any());

        listener.onRouteCancelled(routeCancelled("NOT_STARTED", 2));
        verify(dispatch).sendRoutePush(any(), any(),
                eq("Your route wasn't started within 45 minutes of departure, so it was cancelled and 2 bookings were refunded."), any(), any());
    }

    @Test
    void riderIsOnlyToldAboutARefundWhenTheyPaid() {
        listener.onBookingCancelled(BookingCancelledEvent.builder().bookingId("b-1").riderId("rider-1").cancelledBy("DRIVER").paid(true).build());
        verify(dispatch).sendPush(eq("rider-1"), eq("Booking cancelled"), eq("Your driver cancelled this trip. You've been fully refunded."),
                eq("BOOKING_CANCELLED"), eq("b-1"));

        listener.onBookingCancelled(BookingCancelledEvent.builder().bookingId("b-2").riderId("rider-2").cancelledBy("DRIVER").paid(false).build());
        verify(dispatch).sendPush(eq("rider-2"), eq("Booking cancelled"), eq("Your driver cancelled this trip."), eq("BOOKING_CANCELLED"), eq("b-2"));
    }

    @Test
    void reservedSeatCountsSeats() {
        listener.onSeatReserved(SeatReservedEvent.builder().riderId("rider-1").bookingId("b-1").driverName("Tunde").seatCount(1).build());
        verify(dispatch).sendPush(any(), any(),
                eq("Tunde just posted a new trip and held 1 seat for you - accept within 15 minutes before it's released."), any(), any());

        listener.onSeatReserved(SeatReservedEvent.builder().riderId("rider-1").bookingId("b-2").driverName("Tunde").seatCount(2).build());
        verify(dispatch).sendPush(any(), any(),
                eq("Tunde just posted a new trip and held 2 seats for you - accept within 15 minutes before it's released."), any(), any());
    }

    private static RouteDepartureReminderEvent reminder(String kind, int riders) {
        List<RouteDepartureReminderEvent.RiderBooking> booked = java.util.stream.IntStream.range(0, riders)
                .mapToObj(i -> new RouteDepartureReminderEvent.RiderBooking("rider-" + i, "booking-" + i)).toList();
        return RouteDepartureReminderEvent.builder()
                .kind(kind).routePostId("route-1").driverId("driver-1")
                .originLabel("Lekki").destinationLabel("Ikoyi")
                .departureTime(Instant.now().plusSeconds(29 * 60 + 30))
                .riders(booked)
                .build();
    }

    private static RouteCancelledEvent routeCancelled(String cancelledBy, int refunded) {
        return RouteCancelledEvent.builder()
                .routePostId("route-1").driverId("driver-1").cancelledBy(cancelledBy)
                .affectedBookingIds(java.util.stream.IntStream.range(0, refunded).mapToObj(i -> "b-" + i).toList())
                .build();
    }

    private static org.coryd.common.kafka.events.SosTriggeredEvent.SosTriggeredEventBuilder sosEvent() {
        return org.coryd.common.kafka.events.SosTriggeredEvent.builder()
                .incidentId("sos-1").bookingId("booking-1").triggeredBy("driver-1").triggeredByRole("DRIVER")
                .riderPhone("08030000000").driverPhone("08040000000").driverName("Tunde").vehiclePlate("LND123AB")
                .routeSummary("Eko Hotel → Ikorodu").latitude(6.45).longitude(3.39).contacts(List.of());
    }
}

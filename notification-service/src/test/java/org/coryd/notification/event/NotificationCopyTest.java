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
    private final NotificationEventListener listener = new NotificationEventListener(dispatch);

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
}

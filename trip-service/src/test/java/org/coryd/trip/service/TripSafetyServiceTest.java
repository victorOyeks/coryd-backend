package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.TripSafetyCheckEvent;
import org.coryd.common.kafka.events.TripSafetyEscalatedEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.TripSafetyProperties;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.TripSafetyAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Are you OK?" - when it's asked, what each answer does, and when ops hears. */
@SuppressWarnings("unchecked")
class TripSafetyServiceTest {

    private final TripSafetyAlertRepository alerts = mock(TripSafetyAlertRepository.class);
    private final RoutePostRepository routes = mock(RoutePostRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final IdentityServiceClient identity = mock(IdentityServiceClient.class);
    private final ProfileServiceClient profile = mock(ProfileServiceClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final TripSafetyService service = new TripSafetyService(new TripSafetyProperties(), alerts, routes, bookings,
            bookingService, identity, profile, redis, kafka);

    private final RoutePost route = RoutePost.builder().id("route-1").driverId("driver-1").status(RouteStatus.IN_PROGRESS)
            .originLabel("Ikorodu").destinationLabel("VI").originLat(6.62).originLng(3.51).destinationLat(6.43).destinationLng(3.42).build();
    private final Booking rider = Booking.builder().id("booking-1").routePostId("route-1").riderId("rider-1")
            .status(BookingStatus.IN_PROGRESS).pickupLat(6.62).pickupLng(3.51).build();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(routes.findById("route-1")).thenReturn(Optional.of(route));
        when(bookings.findByRoutePostId("route-1")).thenReturn(List.of(rider));
        when(alerts.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aLongStopAwayFromAnyPickupAsksEveryoneOnBoard() {
        long elevenMinutesAgo = Instant.now().minus(11, ChronoUnit.MINUTES).toEpochMilli();
        when(values.get(TripSafetyService.STOP_KEY + "route-1")).thenReturn("6.50,3.45," + elevenMinutesAgo);
        when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        service.onLocation("route-1", 6.50, 3.45);

        verify(kafka).send(eq(KafkaTopics.TRIP_SAFETY_CHECK), argThat(e -> {
            var event = (TripSafetyCheckEvent) e;
            return "STOPPED".equals(event.getKind()) && event.getUserIds().containsAll(List.of("driver-1", "rider-1"));
        }));
    }

    @Test
    void waitingAtAPickupIsNotAWarningSign() {
        long elevenMinutesAgo = Instant.now().minus(11, ChronoUnit.MINUTES).toEpochMilli();
        when(values.get(TripSafetyService.STOP_KEY + "route-1")).thenReturn("6.62,3.51," + elevenMinutesAgo);

        service.onLocation("route-1", 6.62, 3.51);

        verify(kafka, never()).send(eq(KafkaTopics.TRIP_SAFETY_CHECK), any());
    }

    @Test
    void iNeedHelpRaisesAnSosForWhoeverAsked() {
        TripSafetyAlert alert = openAlert();

        service.respond("alert-1", "rider-1", false, 6.5, 3.4);

        verify(bookingService).triggerSos("booking-1", "rider-1", 6.5, 3.4);
        assertThat(alert.getStatus()).isEqualTo(TripSafetyAlert.Status.HELP);

        service.respond("alert-1", "driver-1", false, null, null);
        verify(bookingService).triggerRouteSos("route-1", "driver-1", null, null);
    }

    @Test
    void everyoneFineClosesItAndStrangersCantAnswer() {
        TripSafetyAlert alert = openAlert();

        service.respond("alert-1", "driver-1", true, null, null);
        assertThat(alert.getStatus()).isEqualTo(TripSafetyAlert.Status.OPEN);
        service.respond("alert-1", "rider-1", true, null, null);
        assertThat(alert.getStatus()).isEqualTo(TripSafetyAlert.Status.ALL_OK);

        assertThatThrownBy(() -> service.respond("alert-1", "someone-else", true, null, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anUnansweredCheckAlertsOps() {
        TripSafetyAlert alert = openAlert();
        when(alerts.findByStatusAndCreatedAtBefore(eq(TripSafetyAlert.Status.OPEN), any())).thenReturn(List.of(alert));
        when(identity.fetchPhone("rider-1")).thenReturn("0803");
        when(identity.fetchPhone("driver-1")).thenReturn("0804");
        when(profile.fetchDriverName("driver-1")).thenReturn("Kunle Adeyemi");

        service.escalateUnanswered();

        assertThat(alert.getStatus()).isEqualTo(TripSafetyAlert.Status.ESCALATED);
        verify(kafka).send(eq(KafkaTopics.TRIP_SAFETY_ESCALATED), argThat(e -> {
            var event = (TripSafetyEscalatedEvent) e;
            return event.getUnanswered() == 2 && "0803".equals(event.getRiderPhones()) && "Ikorodu → VI".equals(event.getRouteSummary())
                    && "driver Kunle Adeyemi 0804, rider 0803".equals(event.getNotAnswered()) && event.getAnsweredOk() == null;
        }));
    }

    @Test
    void theAlertSaysWhoAnsweredAndWhoDidnt() {
        TripSafetyAlert alert = openAlert();
        alert.getAnswers().put("driver-1", TripSafetyAlert.Answer.FINE);
        when(alerts.findByStatusAndCreatedAtBefore(eq(TripSafetyAlert.Status.OPEN), any())).thenReturn(List.of(alert));
        when(identity.fetchPhone("rider-1")).thenReturn("0803");
        when(identity.fetchPhone("driver-1")).thenReturn("0804");
        when(profile.fetchDriverName("driver-1")).thenReturn("Kunle Adeyemi");

        service.escalateUnanswered();

        verify(kafka).send(eq(KafkaTopics.TRIP_SAFETY_ESCALATED), argThat(e -> {
            var event = (TripSafetyEscalatedEvent) e;
            return "rider 0803".equals(event.getNotAnswered()) && "driver Kunle Adeyemi 0804".equals(event.getAnsweredOk());
        }));
    }

    @Test
    void theSameKindOfCheckIsNotRaisedAgainWithinTheRepeatWindow() {
        when(alerts.existsByRoutePostIdAndKindAndCreatedAtAfter(eq("route-1"), eq(TripSafetyAlert.Kind.SIGNAL_LOST), any())).thenReturn(true);

        service.raise(route, TripSafetyAlert.Kind.SIGNAL_LOST, null, null);

        verify(alerts, never()).save(any());
        verify(kafka, never()).send(eq(KafkaTopics.TRIP_SAFETY_CHECK), any());
    }

    @Test
    void aTripLongPastItsExpectedArrivalIsNoLongerWatched() {
        RoutePost stuck = RoutePost.builder().id("route-old").driverId("driver-1").status(RouteStatus.IN_PROGRESS)
                .departureTime(Instant.now().minus(15, ChronoUnit.HOURS)).estimatedDurationMinutes(60).build();
        when(routes.findByStatus(RouteStatus.IN_PROGRESS)).thenReturn(List.of(stuck));
        when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        service.checkSignalLost();

        verify(alerts, never()).save(any());
        verify(kafka, never()).send(eq(KafkaTopics.TRIP_SAFETY_CHECK), any());
    }

    @Test
    void aTripWithinItsExpectedTimeIsStillWatched() {
        RoutePost live = RoutePost.builder().id("route-live").driverId("driver-1").status(RouteStatus.IN_PROGRESS)
                .startedAt(Instant.now().minus(30, ChronoUnit.MINUTES)).estimatedDurationMinutes(60).build();
        when(routes.findByStatus(RouteStatus.IN_PROGRESS)).thenReturn(List.of(live));
        when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(bookings.findByRoutePostId("route-live")).thenReturn(List.of());

        service.checkSignalLost();

        verify(kafka).send(eq(KafkaTopics.TRIP_SAFETY_CHECK), argThat(e -> "SIGNAL_LOST".equals(((TripSafetyCheckEvent) e).getKind())));
        assertThat(service.watching(live, Instant.now().plus(89, ChronoUnit.MINUTES))).isTrue();
        assertThat(service.watching(live, Instant.now().plus(91, ChronoUnit.MINUTES))).isFalse();
    }

    private TripSafetyAlert openAlert() {
        HashMap<String, TripSafetyAlert.Answer> answers = new HashMap<>();
        answers.put("driver-1", TripSafetyAlert.Answer.WAITING);
        answers.put("rider-1", TripSafetyAlert.Answer.WAITING);
        TripSafetyAlert alert = TripSafetyAlert.builder().id("alert-1").routePostId("route-1")
                .kind(TripSafetyAlert.Kind.STOPPED).answers(answers).build();
        when(alerts.findById("alert-1")).thenReturn(Optional.of(alert));
        return alert;
    }
}

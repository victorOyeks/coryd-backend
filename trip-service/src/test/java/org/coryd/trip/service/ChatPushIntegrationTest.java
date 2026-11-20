package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.ChatMessageType;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.ChatMessageSentEvent;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.dto.SendChatMessageRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.UserBlock;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.ChatMessageRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.UserBlockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A group-chat message is announced on Kafka for the push, to everyone else in the chat - against a throwaway Postgres
 * container built from trip-service's own migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({ChatService.class, UserBlockService.class})
class ChatPushIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-1";

    @Autowired ChatService chatService;
    @Autowired RoutePostRepository routes;
    @Autowired BookingRepository bookings;
    @Autowired ChatMessageRepository messages;
    @Autowired UserBlockRepository blocks;
    @Autowired PlatformTransactionManager transactionManager;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean SimpMessagingTemplate messagingTemplate;
    @MockBean ProfileServiceClient profileServiceClient;

    @BeforeEach
    void cleanSlate() {
        messages.deleteAll();
        blocks.deleteAll();
        bookings.deleteAll();
        routes.deleteAll();
        clearInvocations(kafkaTemplate);
        when(profileServiceClient.fetchRiderSummaries(anyList())).thenAnswer(i -> {
            List<String> ids = i.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id,
                    id -> new ProfileServiceClient.RiderSummaryItem(id, "Ada", "Okafor-" + id, null)));
        });
        when(profileServiceClient.fetchDriverSummaries(anyList())).thenReturn(Map.of(DRIVER,
                new ProfileServiceClient.DriverSummaryItem(DRIVER, "Tunde", "Bakare", null, null)));
    }

    @Test
    void aRidersMessageGoesToTheDriverAndOtherLiveRidersButNotToCancelledOrNoShowRidersOrTheSender() {
        RoutePost route = route(RouteStatus.IN_PROGRESS);
        seat(route, "rider-1", BookingStatus.IN_PROGRESS);
        Booking other = seat(route, "rider-2", BookingStatus.CONFIRMED);
        seat(route, "rider-3", BookingStatus.CANCELLED_RIDER);
        seat(route, "rider-4", BookingStatus.NO_SHOW);
        seat(route, "rider-5", BookingStatus.HELD);

        chatService.sendMessage(route.getId(), "rider-1", text("Running 2 mins late"));

        ChatMessageSentEvent event = published(route);
        assertThat(event.getRecipients()).extracting(ChatMessageSentEvent.Recipient::getUserId,
                        ChatMessageSentEvent.Recipient::getRole, ChatMessageSentEvent.Recipient::getBookingId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DRIVER, "DRIVER", null),
                        org.assertj.core.groups.Tuple.tuple("rider-2", "RIDER", other.getId()));
        assertThat(event.getRouteLabel()).isEqualTo("Ikeja City Mall → Victoria Island");
        assertThat(event.getSenderId()).isEqualTo("rider-1");
        assertThat(event.getSenderName()).isEqualTo("Ada O.");
        assertThat(event.getSenderRole()).isEqualTo("RIDER");
        assertThat(event.getMessageType()).isEqualTo("TEXT");
        assertThat(event.getPreview()).isEqualTo("Running 2 mins late");
        assertThat(event.getMessageId()).isNotNull();
        assertThat(event.getSentAt()).isNotNull();
    }

    @Test
    void theDriversVoiceNoteGoesToEveryLiveRiderAsAVoiceNote() {
        RoutePost route = route(RouteStatus.PUBLISHED);
        seat(route, "rider-1", BookingStatus.CONFIRMED);
        seat(route, "rider-2", BookingStatus.AT_PICKUP);

        chatService.sendMessage(route.getId(), DRIVER,
                new SendChatMessageRequest(ChatMessageType.VOICE, "https://cdn/voice.m4a", 4, "voice/abc"));

        ChatMessageSentEvent event = published(route);
        assertThat(event.getRecipients()).extracting(ChatMessageSentEvent.Recipient::getUserId)
                .containsExactlyInAnyOrder("rider-1", "rider-2");
        assertThat(event.getSenderName()).isEqualTo("Tunde B.");
        assertThat(event.getSenderRole()).isEqualTo("DRIVER");
        assertThat(event.getPreview()).isEqualTo("🎤 Voice note");
    }

    @Test
    void anyoneKeptApartFromTheSenderByABlockEitherWayGetsNoPush() {
        RoutePost route = route(RouteStatus.IN_PROGRESS);
        seat(route, "rider-1", BookingStatus.IN_PROGRESS);
        seat(route, "rider-2", BookingStatus.IN_PROGRESS);
        seat(route, "rider-3", BookingStatus.IN_PROGRESS);
        blocks.save(UserBlock.builder().blockerId("rider-2").blockedId("rider-1").build());
        blocks.save(UserBlock.builder().blockerId("rider-1").blockedId("rider-3").build());

        chatService.sendMessage(route.getId(), "rider-1", text("Hi all"));

        assertThat(published(route).getRecipients()).extracting(ChatMessageSentEvent.Recipient::getUserId)
                .containsExactly(DRIVER);
    }

    @Test
    void aDriverAloneOnTheirRouteSendsNothing() {
        RoutePost route = route(RouteStatus.PUBLISHED);

        chatService.sendMessage(route.getId(), DRIVER, text("Anyone?"));

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.CHAT_MESSAGE_SENT), anyString(), any());
    }

    @Test
    void aMessageWhoseTransactionRollsBackIsNeverAnnounced() {
        RoutePost route = route(RouteStatus.IN_PROGRESS);
        seat(route, "rider-1", BookingStatus.IN_PROGRESS);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            chatService.sendMessage(route.getId(), DRIVER, text("Never saved"));
            status.setRollbackOnly();
        });

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.CHAT_MESSAGE_SENT), anyString(), any());
        assertThat(messages.count()).isZero();
    }

    @Test
    void aLongMessageIsCutToAboutAHundredCharactersOnOneLine() {
        String longText = "a".repeat(60) + "\n\n" + "b".repeat(80);
        String preview = ChatService.preview(ChatMessageType.TEXT, longText);
        assertThat(preview).hasSize(101).endsWith("…").doesNotContain("\n");
        assertThat(ChatService.preview(ChatMessageType.TEXT, "  short  ")).isEqualTo("short");
        // An emoji straddling the cut is dropped whole, never halved.
        assertThat(ChatService.preview(ChatMessageType.TEXT, "x".repeat(99) + "😀😀")).isEqualTo("x".repeat(99) + "…");
        assertThat(ChatService.shortName("Tunde Adewale Bakare")).isEqualTo("Tunde B.");
        assertThat(ChatService.shortName("Tunde")).isEqualTo("Tunde");
        assertThat(ChatService.shortName(null)).isNull();
    }

    private ChatMessageSentEvent published(RoutePost route) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.CHAT_MESSAGE_SENT), eq(route.getId()), event.capture());
        return (ChatMessageSentEvent) event.getValue();
    }

    private static SendChatMessageRequest text(String content) {
        return new SendChatMessageRequest(ChatMessageType.TEXT, content, null, null);
    }

    private RoutePost route(RouteStatus status) {
        Instant departure = Instant.now().plus(30, ChronoUnit.MINUTES);
        return routes.save(RoutePost.builder().driverId(DRIVER).vehicleId("vehicle-1")
                .originLabel("Ikeja City Mall").originLat(6.61).originLng(3.36)
                .destinationLabel("Victoria Island").destinationLat(6.43).destinationLng(3.42)
                .departureTime(departure).seatCapacity(4).seatsAvailable(4).farePerSeat(new BigDecimal("1200.00"))
                .status(status).startedAt(status == RouteStatus.IN_PROGRESS ? departure : null).build());
    }

    private Booking seat(RoutePost route, String riderId, BookingStatus status) {
        return bookings.save(Booking.builder().routePostId(route.getId()).riderId(riderId).seatCount(1)
                .fareTotal(new BigDecimal("1200.00")).status(status).holdExpiresAt(Instant.now()).confirmedAt(Instant.now()).build());
    }
}

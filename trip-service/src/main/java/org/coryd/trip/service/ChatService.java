package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.ChatMessageType;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.dto.ChatMessageResponse;
import org.coryd.trip.dto.SendChatMessageRequest;
import org.coryd.trip.entity.ChatMessage;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.ChatMessageRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trip Group Chat - one thread per route post, membership computed the same way
 * WebSocketAuthInterceptor/LocationService already do it (driver, or any rider with a
 * non-terminal booking on this route) rather than a separate membership table: a rider is "in"
 * the chat exactly as long as their booking is live, no explicit add/remove step needed on
 * booking or cancellation. Every message is deleted the moment the route completes or is
 * cancelled - see BookingService.completeRoute() and RouteService.cancel().
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Set<BookingStatus> NON_TERMINAL_BOOKING_STATUSES =
            Set.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    private final ChatMessageRepository chatMessageRepository;
    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProfileServiceClient profileServiceClient;

    public List<ChatMessageResponse> getMessages(String routePostId, String callerId) {
        RoutePost route = requireParty(routePostId, callerId);
        List<ChatMessage> messages = chatMessageRepository.findByRoutePostIdOrderByCreatedAtAsc(routePostId);
        return enrich(messages, route);
    }

    @Transactional
    public ChatMessageResponse sendMessage(String routePostId, String callerId, SendChatMessageRequest request) {
        RoutePost route = requireParty(routePostId, callerId);

        ChatMessage message = chatMessageRepository.save(ChatMessage.builder()
                .routePostId(routePostId)
                .senderId(callerId)
                .type(request.type())
                .content(request.content())
                .durationSeconds(request.durationSeconds())
                .mediaPublicId(request.mediaPublicId())
                .build());

        ChatMessageResponse response = enrich(List.of(message), route).get(0);
        messagingTemplate.convertAndSend("/topic/routes/" + routePostId + "/chat", response);
        return response;
    }

    /** One driver lookup + one batched rider-summaries lookup for the whole thread, not one call
     * per message - a route's driver is fixed, and its riders are exactly whoever has ever sent a
     * message here, which is at most the route's booked riders. */
    private List<ChatMessageResponse> enrich(List<ChatMessage> messages, RoutePost route) {
        Set<String> senderIds = messages.stream().map(ChatMessage::getSenderId).collect(Collectors.toSet());
        String driverId = route.getDriverId();

        Map<String, ProfileServiceClient.DriverSummaryItem> driverById = senderIds.contains(driverId)
                ? profileServiceClient.fetchDriverSummaries(List.of(driverId))
                : Map.of();

        List<String> riderSenderIds = senderIds.stream().filter(id -> !id.equals(driverId)).toList();
        Map<String, ProfileServiceClient.RiderSummaryItem> riderById = riderSenderIds.isEmpty()
                ? Map.of()
                : profileServiceClient.fetchRiderSummaries(riderSenderIds);

        return messages.stream().map(m -> {
            String senderName = null;
            String senderPhotoUrl = null;
            if (m.getSenderId().equals(driverId)) {
                ProfileServiceClient.DriverSummaryItem driver = driverById.get(m.getSenderId());
                if (driver != null) {
                    senderName = driver.legalName();
                    senderPhotoUrl = driver.photoUrl();
                }
            } else {
                ProfileServiceClient.RiderSummaryItem rider = riderById.get(m.getSenderId());
                if (rider != null) {
                    senderName = rider.fullName();
                    senderPhotoUrl = rider.photoUrl();
                }
            }
            return new ChatMessageResponse(m.getId(), m.getRoutePostId(), m.getSenderId(), senderName, senderPhotoUrl,
                    m.getType(), m.getContent(), m.getDurationSeconds(), m.getMediaPublicId(), m.getCreatedAt());
        }).toList();
    }

    /** Called once a route reaches COMPLETED or CANCELLED (and by the dev route-reset tool) - the
     * whole conversation is gone, not just marked deleted, and any voice notes are purged from
     * Cloudinary too, not just this table. Returns how many messages were deleted. */
    @Transactional
    public long deleteChatForRoute(String routePostId) {
        List<ChatMessage> messages = chatMessageRepository.findByRoutePostIdOrderByCreatedAtAsc(routePostId);
        chatMessageRepository.deleteByRoutePostId(routePostId);

        messages.stream()
                .filter(m -> m.getType() == ChatMessageType.VOICE && m.getMediaPublicId() != null)
                .forEach(m -> profileServiceClient.deleteMedia(m.getMediaPublicId(), "video"));

        return messages.size();
    }

    private RoutePost requireParty(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        // The conversation is gone once the route is COMPLETED/CANCELLED (deleteChatForRoute already
        // ran) - without this, the driver's own isDriver check below has no route-status condition
        // of its own, so they could otherwise still write new messages into an already-deleted chat.
        if (route.getStatus() == RouteStatus.COMPLETED || route.getStatus() == RouteStatus.CANCELLED) {
            throw new UnauthorizedException("This trip's group chat has ended");
        }

        boolean isDriver = callerId.equals(route.getDriverId());
        boolean isActiveRider = bookingRepository.findByRoutePostId(routePostId).stream()
                .anyMatch(b -> callerId.equals(b.getRiderId()) && NON_TERMINAL_BOOKING_STATUSES.contains(b.getStatus()));
        if (!isDriver && !isActiveRider) {
            throw new UnauthorizedException("Not authorized for this trip's group chat");
        }
        return route;
    }
}

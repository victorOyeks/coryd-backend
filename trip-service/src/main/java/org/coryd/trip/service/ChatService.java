package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
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
import java.util.Set;

/**
 * Trip Group Chat - one thread per route post, membership computed the same way
 * WebSocketAuthInterceptor/LocationService already do it (driver, or any rider with a
 * non-terminal booking on this route) rather than a separate membership table: a rider is "in"
 * the chat exactly as long as their booking is live, no explicit add/remove step needed on
 * booking or cancellation. Every message is deleted the moment the route completes -
 * see BookingService.completeRoute().
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

    public List<ChatMessage> getMessages(String routePostId, String callerId) {
        requireParty(routePostId, callerId);
        return chatMessageRepository.findByRoutePostIdOrderByCreatedAtAsc(routePostId);
    }

    @Transactional
    public ChatMessage sendMessage(String routePostId, String callerId, SendChatMessageRequest request) {
        requireParty(routePostId, callerId);

        ChatMessage message = chatMessageRepository.save(ChatMessage.builder()
                .routePostId(routePostId)
                .senderId(callerId)
                .type(request.type())
                .content(request.content())
                .durationSeconds(request.durationSeconds())
                .build());

        messagingTemplate.convertAndSend("/topic/routes/" + routePostId + "/chat", message);
        return message;
    }

    /** Called once, right after a route is marked COMPLETED - the whole conversation is gone, not just marked deleted. */
    @Transactional
    public void deleteChatForRoute(String routePostId) {
        chatMessageRepository.deleteByRoutePostId(routePostId);
    }

    private void requireParty(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        boolean isDriver = callerId.equals(route.getDriverId());
        boolean isActiveRider = bookingRepository.findByRoutePostId(routePostId).stream()
                .anyMatch(b -> callerId.equals(b.getRiderId()) && NON_TERMINAL_BOOKING_STATUSES.contains(b.getStatus()));
        if (!isDriver && !isActiveRider) {
            throw new UnauthorizedException("Not authorized for this trip's group chat");
        }
    }
}

package org.coryd.trip.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates the STOMP CONNECT frame with the same JwtUtil plain REST calls use (the token
 * travels as a STOMP native header, not a query param, so it never ends up in a URL/access log)
 * and, on SUBSCRIBE, checks the caller is actually a party to the booking they're trying to
 * read location updates for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Pattern LOCATION_TOPIC = Pattern.compile("^/topic/bookings/([^/]+)/location$");

    private final JwtUtil jwtUtil;
    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            CoRydUserDetails userDetails = authenticate(accessor.getFirstNativeHeader("Authorization"));
            accessor.setUser(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }

        return message;
    }

    private CoRydUserDetails authenticate(String authHeader) {
        String token = extractBearerToken(authHeader);
        if (token == null || !jwtUtil.isTokenValid(token)) {
            throw new MessagingException("Missing or invalid token on WebSocket CONNECT");
        }
        return jwtUtil.extractUserDetails(token);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        Matcher matcher = LOCATION_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return;
        }

        String bookingId = matcher.group(1);
        String userId = currentUserId(accessor);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new MessagingException("Booking not found"));
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new MessagingException("Route post not found"));

        boolean isParty = userId.equals(booking.getRiderId()) || userId.equals(route.getDriverId());
        if (!isParty) {
            log.warn("Rejected WebSocket subscribe to {} - user {} is not a party to booking {}", destination, userId, bookingId);
            throw new MessagingException("Not authorized for this trip's location updates");
        }
    }

    /** Defensive against a CONNECT that never ran (or set something unexpected) - fails as a clean rejection, not an NPE/ClassCastException. */
    private String currentUserId(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)
                || !(auth.getPrincipal() instanceof CoRydUserDetails userDetails)) {
            throw new MessagingException("Not authenticated for this subscription");
        }
        return userDetails.getUserId();
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length());
    }
}

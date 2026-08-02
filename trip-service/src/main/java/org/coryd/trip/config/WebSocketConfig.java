package org.coryd.trip.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP for live trip location, broadcasting to /topic/bookings/{bookingId}/location. Two
 * endpoints, same broker: /ws (SockJS) for browser clients that may lack real WebSocket support,
 * and /ws-native (plain WebSocket upgrade, no SockJS handshake/framing) for the mobile app -
 * React Native already has a native WebSocket implementation, so SockJS's browser-fallback
 * transports (and its dependency on browser globals like `document`) only add overhead there.
 * An in-memory broker is enough at this scale (one process, modest concurrent trip count) - no
 * external broker like RabbitMQ needed. Auth happens in WebSocketAuthInterceptor, wired below on
 * the inbound channel - it operates at the STOMP frame level, so it applies identically to both
 * endpoints regardless of transport.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/ws-native").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}

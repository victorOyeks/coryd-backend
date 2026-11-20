package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Someone sent a message in a trip's group chat - everyone else in the chat gets a push. trip-service works out the
 * recipients (the driver and every rider with a live booking, minus the sender and anyone kept apart from the sender
 * by a block), so notification-service only has to word and send it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageSentEvent {
    private String routePostId;
    /** "Ikeja City Mall → Victoria Island" - the route's own origin and destination labels. */
    private String routeLabel;
    private String messageId;
    private String senderId;
    /** "Tunde A." - first name and last initial; null when the profile lookup failed. */
    private String senderName;
    /** DRIVER or RIDER. */
    private String senderRole;
    /** TEXT or VOICE. */
    private String messageType;
    /** The text trimmed to about 100 characters, or "🎤 Voice note". */
    private String preview;
    private Instant sentAt;
    private List<Recipient> recipients;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {
        private String userId;
        /** DRIVER or RIDER. */
        private String role;
        /** The rider's booking on this route - null for the driver. */
        private String bookingId;
    }
}

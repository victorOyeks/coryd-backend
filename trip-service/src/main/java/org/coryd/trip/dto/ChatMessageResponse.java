package org.coryd.trip.dto;

import org.coryd.common.enums.ChatMessageType;

import java.time.Instant;

/**
 * senderName/senderPhotoUrl are resolved here, not stored on the ChatMessage row - a user's
 * profile can change after they sent a message, and resolving at read time (batched across the
 * whole thread, one driver lookup + one rider-summaries lookup per request) keeps every message
 * showing the sender's CURRENT name/photo rather than a snapshot that could go stale or outlive
 * a profile edit. Both are null if that lookup didn't find a match (e.g. the profile-service call
 * failed) - the sender is still identifiable by senderId in that case.
 */
public record ChatMessageResponse(
        String id,
        String routePostId,
        String senderId,
        String senderName,
        String senderPhotoUrl,
        ChatMessageType type,
        String content,
        Integer durationSeconds,
        String mediaPublicId,
        Instant createdAt
) {
}

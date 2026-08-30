package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.ChatMessageType;

/** content is the text body for TEXT, or the already-uploaded audio URL for VOICE (upload it first via POST /media/upload on profile-service). durationSeconds and mediaPublicId only apply to VOICE - mediaPublicId is that same upload response's publicId, kept so the audio can be purged from Cloudinary once the trip's chat is deleted. */
public record SendChatMessageRequest(
        @NotNull ChatMessageType type,
        @NotBlank String content,
        Integer durationSeconds,
        String mediaPublicId
) {
}

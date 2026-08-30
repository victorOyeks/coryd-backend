package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.ChatMessageType;

/** content is the text body for TEXT, or the already-uploaded audio URL for VOICE (upload it first via POST /media/upload on profile-service). durationSeconds only applies to VOICE. */
public record SendChatMessageRequest(
        @NotNull ChatMessageType type,
        @NotBlank String content,
        Integer durationSeconds
) {
}

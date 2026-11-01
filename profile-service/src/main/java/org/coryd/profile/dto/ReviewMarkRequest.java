package org.coryd.profile.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.ReviewMark;

/** note is required to flag - it becomes the reason the driver is shown, so at most 255 characters then. markedBy is set by ops-service. */
public record ReviewMarkRequest(
        @NotNull ReviewMark mark,
        @Size(max = 500) String note,
        @Size(max = 150) String markedBy
) {
}

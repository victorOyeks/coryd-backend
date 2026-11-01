package org.coryd.ops.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.ReviewMark;

/** VERIFIED, or FLAGGED with a note - the note is required to flag, and becomes the reason the driver sees (255 characters at most). */
public record ReviewMarkRequest(@NotNull ReviewMark mark, @Size(max = 500) String note) {
}

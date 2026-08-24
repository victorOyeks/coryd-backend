package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.DocumentType;

import java.time.LocalDate;

public record CreateDriverDocumentRequest(
        @NotNull DocumentType documentType,
        @NotBlank String url,
        @NotBlank String publicId,
        LocalDate expiresOn,
        String vehicleId
) {
}

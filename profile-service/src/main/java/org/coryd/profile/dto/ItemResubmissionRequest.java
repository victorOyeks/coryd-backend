package org.coryd.profile.dto;

import jakarta.validation.constraints.Size;

/** Ask the driver to upload one document again. reason is required - the driver sees it. reviewedBy is set by ops-service. */
public record ItemResubmissionRequest(@Size(max = 255) String reason, @Size(max = 150) String reviewedBy) {
}

package org.coryd.ops.dto;

import jakarta.validation.constraints.Size;

/** Optional note, kept with each approval in the audit log. */
public record ApproveDriverRequest(@Size(max = 500) String note) {
}

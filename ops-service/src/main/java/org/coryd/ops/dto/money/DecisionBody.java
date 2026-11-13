package org.coryd.ops.dto.money;

import jakarta.validation.constraints.Size;

/** Optional on approve and cancel; required on reject. */
public record DecisionBody(@Size(max = 500) String reason) {
}

package org.coryd.payment.ops.dto;

import jakarta.validation.constraints.Size;

/** Approve and cancel take an optional note; reject needs one (checked in the service). */
public record DecisionRequest(@Size(max = 500) String reason) {
}

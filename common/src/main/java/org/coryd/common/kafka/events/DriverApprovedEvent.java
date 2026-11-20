package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverApprovedEvent {
    private String driverId;
    private String vehicleId;
    private String email;
    private String reviewedBy;
    private LocalDateTime approvedAt;
    /** True only the first time the driver is fully verified - every item approved and, while
     * IDENTITY_VERIFICATION_ENFORCEMENT is STRICT, their identity (NIN) check too: the one "You're verified!". False when a
     * verified driver is approved again (a renewed or re-uploaded document), or when their items are approved but their
     * identity isn't yet. Null from older senders means a first approval. */
    private Boolean firstApproval;
    /** True when every item is approved but the driver's identity (NIN) check isn't yet, so they can't post routes - they're
     * told their documents are approved and to finish the NIN check. Null from older senders means false. trip-service
     * treats the event the same either way: its document gate is passed, the identity gate is its own. */
    private Boolean identityPending;
}

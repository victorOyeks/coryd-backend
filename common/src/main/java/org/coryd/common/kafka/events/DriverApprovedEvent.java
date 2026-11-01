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
    /** False when a verified driver is approved again (a renewed or re-uploaded document) - they can post again, but
     * aren't welcomed as if new. Null from older senders means a first approval. */
    private Boolean firstApproval;
}

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
public class VerificationItemDecidedEvent {
    private String driverId;
    private String email;
    private String item;
    private String itemLabel;
    private boolean approved;
    private String rejectionReason;
    private String reviewedBy;
    private LocalDateTime decidedAt;
}

package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSuspendedEvent {
    private String userId;
    private String reason;
    private String suspendedBy;
    private Instant suspendedAt;
}

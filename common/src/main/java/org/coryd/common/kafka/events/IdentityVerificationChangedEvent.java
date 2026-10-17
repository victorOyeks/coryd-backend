package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A person's NIN + selfie check was approved or rejected - riders and drivers alike. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityVerificationChangedEvent {
    private String userId;
    private boolean verified;
    /** SMILE_ID for an automatic match (PREMBLY on older events), otherwise the ops user who decided. */
    private String decidedBy;
    private Instant decidedAt;
}

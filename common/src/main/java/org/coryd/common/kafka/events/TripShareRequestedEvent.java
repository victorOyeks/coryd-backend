package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripShareRequestedEvent {
    private String bookingId;
    private String riderId;
    private String shareLink;
    /** Every saved emergency contact, not just the primary. */
    private List<EmergencyContactPayload> contacts;
    private LocalDateTime requestedAt;
}

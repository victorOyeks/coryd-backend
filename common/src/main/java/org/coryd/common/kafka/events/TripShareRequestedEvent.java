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
public class TripShareRequestedEvent {
    private String bookingId;
    private String riderId;
    private String shareLink;
    private String contactName;
    private String contactPhone;
    private LocalDateTime requestedAt;
}

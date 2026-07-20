package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentExpiringEvent {
    private String driverId;
    private String documentType;
    private LocalDate expiresOn;
    private int daysRemaining;
}

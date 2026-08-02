package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One rider-saved emergency contact, as carried on events that alert all of them (Share Trip, SOS). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyContactPayload {
    private String name;
    private String phone;
}

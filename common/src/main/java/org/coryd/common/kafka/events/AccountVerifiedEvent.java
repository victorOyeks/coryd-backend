package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.coryd.common.enums.UserRole;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountVerifiedEvent {
    private String userId;
    private String phone;
    private UserRole roleAdded;
    private LocalDateTime verifiedAt;
}

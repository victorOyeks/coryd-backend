package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.coryd.common.enums.OtpChannel;
import org.coryd.common.enums.OtpPurpose;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpIssuedEvent {
    private String phone;
    private String code;
    private OtpChannel channel;
    private OtpPurpose purpose;
}

package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import org.coryd.common.enums.OtpChannel;

public record ResendOtpRequest(
        @NotBlank String phone,
        OtpChannel channel
) {
    public OtpChannel channelOrDefault() {
        return channel == null ? OtpChannel.SMS : channel;
    }
}

package org.coryd.payment.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "paystack")
public class PaystackProperties {
    private String secretKey;
    private String baseUrl = "https://api.paystack.co";

    /** auto (T+1), weekly or monthly. Never "manual" - that is conditional release by another name. */
    private String settlementSchedule = "weekly";

    /**
     * Who pays Paystack's fee on a split charge. Unset leaves Paystack's default, which is the main
     * account - and on a 2,500 fare the fee is more than our 5%, so we would be paying to be booked.
     * The dashboard's "pass transaction fees to customers" does not cover splits on its own.
     */
    private String splitFeeBearer;
}

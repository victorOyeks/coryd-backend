package org.coryd.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Final commission percentage is a business decision outside this document's
 * scope (Section 1) - kept config-driven so Finance can change it without a
 * redeploy. Deducted from the driver's payout, never added on top of the
 * rider's fare (Section 10.4).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "commission")
public class CommissionProperties {
    private java.math.BigDecimal percentage = new java.math.BigDecimal("0.15");
}

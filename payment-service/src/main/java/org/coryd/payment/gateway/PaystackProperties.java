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

    /**
     * Test environments only: Paystack's test bank (its own error message names "001"), sent in place
     * of the driver's real bank so adding an account doesn't burn test mode's 3 real lookups a day.
     * Ignored with a live key - a live account must always be the real one.
     */
    private String testBankCode;

    /**
     * Test environments only: one subaccount created by hand in Paystack's test dashboard, used for
     * every driver - Paystack's test bank resolves accounts but won't take a subaccount. Ignored with a live key.
     */
    private String testSubaccountCode;

    /** The shared test subaccount, when one applies - never with a live key. */
    public String testSubaccount() {
        return testSubaccountCode != null && !testSubaccountCode.isBlank() && isTestKey() ? testSubaccountCode : null;
    }

    public boolean isTestKey() {
        return secretKey != null && secretKey.startsWith("sk_test_");
    }

    /** The bank code Paystack is actually sent. The driver's own details are still what we store and show. */
    public String gatewayBankCode(String bankCode) {
        return testBankCode != null && !testBankCode.isBlank() && isTestKey() ? testBankCode : bankCode;
    }
}

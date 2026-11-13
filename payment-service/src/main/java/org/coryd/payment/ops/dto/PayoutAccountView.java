package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A driver's payout accounts with the number masked ("******6789"). linkedToPaystack: a Paystack subaccount exists, so
 * fares can be split to it. accountName is what the bank returned when the account was saved - show it prominently.
 */
public record PayoutAccountView(String driverId, boolean hasAccount, boolean canReceivePayouts, List<Account> accounts) {

    public record Account(String id, String bankName, String bankCode, String accountName, String accountNumberMasked,
                          String accountNumber, boolean isDefault, boolean linkedToPaystack, String subaccountCode,
                          BigDecimal splitPercentage, String settlementSchedule, Instant addedAt) {
    }
}

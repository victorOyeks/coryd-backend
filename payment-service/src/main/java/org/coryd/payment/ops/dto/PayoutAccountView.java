package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A driver's payout accounts with the number masked ("••••••6789" - a bullet per hidden digit, the same masking as every
 * other identifier in the console, docs/ops-console/privacy.md). linkedToPaystack: a Paystack subaccount exists, so fares
 * can be split to it. accountName is what the bank returned when the account was saved - show it prominently.
 * accountNumber, revealedAt and expiresInSeconds (60 - stop showing it then) are set only on the audited reveal.
 */
public record PayoutAccountView(String driverId, boolean hasAccount, boolean canReceivePayouts, List<Account> accounts) {

    public record Account(String id, String bankName, String bankCode, String accountName, String accountNumberMasked,
                          String accountNumber, boolean isDefault, boolean linkedToPaystack, String subaccountCode,
                          BigDecimal splitPercentage, String settlementSchedule, Instant addedAt, Instant revealedAt,
                          Integer expiresInSeconds) {
    }
}

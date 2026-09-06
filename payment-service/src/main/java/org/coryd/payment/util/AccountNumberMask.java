package org.coryd.payment.util;

/** Shared masking for the two places a driver's payout account is shown back to them - the Wallet
 * feed's withdrawal rows and the withdrawal receipt - so the same account never renders two
 * different ways. */
public final class AccountNumberMask {

    private AccountNumberMask() {
    }

    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return accountNumber;
        }
        return accountNumber.substring(0, 2) + "****" + accountNumber.substring(accountNumber.length() - 2);
    }
}

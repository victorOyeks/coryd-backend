package org.coryd.payment.dev;

/** What the purge actually removed, so the caller can report it rather than guess. */
public record PurgeBookingsResult(
        int fareHolds,
        int paymentTransactions,
        int earningsTransactions,
        int walletTransactions,
        int walletsAdjusted,
        int earningsAccountsAdjusted
) {
}

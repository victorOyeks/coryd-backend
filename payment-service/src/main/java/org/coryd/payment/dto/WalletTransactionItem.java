package org.coryd.payment.dto;

import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in the Wallet screen's mixed Trip/Withdrawal feed - type discriminates which fields are
 * populated. Each row carries everything its card renders, so the screen doesn't have to fan out
 * to trip-service or the payout endpoints per row: a TRIP row carries who paid for it, a
 * WITHDRAWAL row carries the bank account it landed in.
 *
 * <p>routePostId is still the link target for the Trip Receipt screen (trip-service's
 * GET /routes/{routePostId}/receipt) - the origin/destination text itself isn't duplicated here,
 * since that's route data payment-service has no copy of.
 */
public record WalletTransactionItem(
        WalletTransactionType type,
        String id,
        Instant occurredAt,

        // TRIP fields
        String bookingId,
        String routePostId,
        BigDecimal grossFare,
        BigDecimal commissionAmount,
        BigDecimal netCredit,
        Rider rider,

        // WITHDRAWAL fields
        BigDecimal amount,
        PayoutStatus payoutStatus,
        String failureReason,
        BankAccount bankAccount
) {

    /** The rider who paid for this trip. fullName/photoUrl come from profile-service and are null
     * if that lookup failed or the rider has no profile yet - riderId is always present on rows
     * recorded after the rider_id column was added. */
    public record Rider(String riderId, String fullName, String photoUrl) {
    }

    /** The bank account a withdrawal paid out to, as it was at the time of the withdrawal. Null on
     * payouts that predate the recipient snapshot and whose driver has no default account left. */
    public record BankAccount(String bankName, String accountName, String accountNumberMasked) {
    }

    /** Rider identity comes off the earnings row, the display fields off a single batched
     * profile-service call for the whole page - this stitches the two together. */
    public WalletTransactionItem withRider(Rider enriched) {
        return new WalletTransactionItem(type, id, occurredAt, bookingId, routePostId, grossFare,
                commissionAmount, netCredit, enriched, amount, payoutStatus, failureReason, bankAccount);
    }
}

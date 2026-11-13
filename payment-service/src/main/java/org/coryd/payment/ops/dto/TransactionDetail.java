package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One transaction with everything around it. booking: for a payment or a booking refund - the payment, what's refundable,
 * the fare hold, the driver's earning, every refund and ops request on the booking. payout: for a payout.
 */
public record TransactionDetail(TransactionRow transaction, BookingMoney booking, PayoutDetail payout, List<TimelineEntry> timeline) {

    public record BookingMoney(TransactionRow payment, RefundableView refundable, Hold hold, Earning earning,
                               List<TransactionRow> refunds, List<OpsMoneyRequestView> requests) {
    }

    /** The fare's ledger: settled to the driver (released) or refunded, exactly once; refundDue is waiting on Paystack. */
    public record Hold(String id, String driverId, BigDecimal amount, boolean released, boolean refunded, BigDecimal refundDue,
                       BigDecimal debtRecovery, String refundReason) {
    }

    public record Earning(String routePostId, BigDecimal gross, BigDecimal commission, BigDecimal net, Instant creditedAt) {
    }
}

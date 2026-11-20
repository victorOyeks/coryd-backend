package org.coryd.payment.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The card fee the payer covers on top of what they owe (a fare, or a driver's debt) - the one place it is worked out.
 * The booking quote, the repay quote, initiate and the receipt all read it, so what the app shows before paying is what
 * the card is charged.
 *
 * <p>Paystack's local-card pricing: {@code percent} of the charge plus {@code flat}, the flat part waived on charges under
 * {@code flat-waived-under}, the whole fee capped at {@code cap}. Passing it on means charging the total T where
 * T - fee(T) = amount: T = (amount + flat) / (1 - percent), rounded up to the kobo; the flat part applies once T reaches
 * the threshold; T = amount + cap once the fee would pass the cap. Checked against Paystack's own pass-on charges on dev:
 * 3,000 -> 3,147.21, 2,000 -> 2,030.46, 1,500 -> 1,522.85.
 *
 * <p>{@code added-by} says who adds it at charge time. PAYSTACK (the default): the dashboard's "pass charges to customer"
 * is on, so we send the amount and Paystack adds this same fee - sending the total too would charge the fee twice.
 * CORYD: that setting is off and we send the total ourselves. Either way the webhook's real figures are recorded.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.card-fee")
public class CardFeeCalculator {

    public enum AddedBy { PAYSTACK, CORYD }

    private BigDecimal percent = new BigDecimal("0.015");
    private BigDecimal flat = new BigDecimal("100");
    private BigDecimal flatWaivedUnder = new BigDecimal("2500");
    private BigDecimal cap = new BigDecimal("2000");
    private AddedBy addedBy = AddedBy.PAYSTACK;

    /** amount: what the payer owes before the fee. cardFee: the fee on top. total: what the card is charged. */
    public record Quote(BigDecimal amount, BigDecimal cardFee, BigDecimal total) {
    }

    public Quote quote(BigDecimal amount) {
        BigDecimal base = amount.setScale(2, RoundingMode.HALF_UP);
        if (base.signum() <= 0) {
            return new Quote(base, BigDecimal.ZERO.setScale(2), base);
        }
        BigDecimal keep = BigDecimal.ONE.subtract(percent);
        BigDecimal total = base.add(flat).divide(keep, 2, RoundingMode.CEILING);
        if (total.compareTo(flatWaivedUnder) < 0) {
            total = base.divide(keep, 2, RoundingMode.CEILING);
        }
        BigDecimal fee = total.subtract(base);
        if (fee.compareTo(cap) > 0) {
            fee = cap.setScale(2, RoundingMode.HALF_UP);
            total = base.add(fee);
        }
        return new Quote(base, fee, total);
    }

    /** What to ask the gateway for: the total when we add the fee, else the amount (the gateway adds it). */
    public BigDecimal chargeAmount(Quote quote) {
        return addedBy == AddedBy.CORYD ? quote.total() : quote.amount();
    }

    public boolean coRydAddsFee() {
        return addedBy == AddedBy.CORYD;
    }
}

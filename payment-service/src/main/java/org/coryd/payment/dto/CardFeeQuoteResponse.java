package org.coryd.payment.dto;

import org.coryd.payment.service.CardFeeCalculator;

import java.math.BigDecimal;

/**
 * What paying will cost, before paying. amount: what is owed (the fare, or the debt). cardFee: the card fee on top, which
 * the payer covers. total: what the card is charged. Show all three - "Seat ₦3,000 · Card fee ₦147.21 · Total ₦3,147.21".
 */
public record CardFeeQuoteResponse(BigDecimal amount, BigDecimal cardFee, BigDecimal total, String currency) {

    public static CardFeeQuoteResponse of(CardFeeCalculator.Quote quote) {
        return new CardFeeQuoteResponse(quote.amount(), quote.cardFee(), quote.total(), "NGN");
    }
}

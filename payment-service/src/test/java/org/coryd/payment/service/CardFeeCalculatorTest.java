package org.coryd.payment.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CardFeeCalculatorTest {

    private final CardFeeCalculator calculator = new CardFeeCalculator();

    private CardFeeCalculator.Quote quote(String amount) {
        return calculator.quote(new BigDecimal(amount));
    }

    /** The totals Paystack itself charged on dev with "pass charges to customer" on (payment-service logs, 20 Nov 2026). */
    @Test
    void matchesWhatPaystackCharged() {
        assertThat(quote("3000").total()).isEqualByComparingTo("3147.21");
        assertThat(quote("3000").cardFee()).isEqualByComparingTo("147.21");
        assertThat(quote("2000").total()).isEqualByComparingTo("2030.46");
        assertThat(quote("1500").total()).isEqualByComparingTo("1522.85");
    }

    @Test
    void flatPartAppliesOnceTheChargeReachesTheThreshold() {
        // 2,400 + 100 grossed up is 2,538.08 - over 2,500, so the flat part applies.
        assertThat(quote("2400").total()).isEqualByComparingTo("2538.08");
        assertThat(quote("2300").total()).isEqualByComparingTo("2335.03");
    }

    @Test
    void capped() {
        CardFeeCalculator.Quote big = quote("200000");
        assertThat(big.cardFee()).isEqualByComparingTo("2000");
        assertThat(big.total()).isEqualByComparingTo("202000");
    }

    @Test
    void chargeAmountDependsOnWhoAddsTheFee() {
        CardFeeCalculator.Quote q = quote("3000");
        assertThat(calculator.chargeAmount(q)).isEqualByComparingTo("3000");
        calculator.setAddedBy(CardFeeCalculator.AddedBy.CORYD);
        assertThat(calculator.chargeAmount(q)).isEqualByComparingTo("3147.21");
    }
}

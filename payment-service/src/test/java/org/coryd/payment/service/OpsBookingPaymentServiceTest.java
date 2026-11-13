package org.coryd.payment.service;

import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.payment.dto.OpsBookingPayment;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.Refund;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the Ops Console's rider history and receipts show was paid and refunded, for a page of bookings in two queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(OpsBookingPaymentService.class)
class OpsBookingPaymentServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OpsBookingPaymentService service;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired RefundRepository refundRepository;

    @BeforeEach
    void cleanSlate() {
        refundRepository.deleteAll();
        paymentTransactionRepository.deleteAll();
    }

    @Test
    void eachBookingCarriesWhatWasChargedRefundedAndStillOwedBack() {
        PaymentTransaction paid = charge("b-paid", "3600.00", PayoutStatus.PAID);
        refund("b-paid", paid, "1200.00", RefundStatus.REFUNDED);
        refund("b-paid", paid, "600.00", RefundStatus.PENDING);
        charge("b-pending", "1800.00", PayoutStatus.PENDING);

        List<OpsBookingPayment> result = service.forBookings(List.of("b-paid", "b-pending", "b-none", "b-paid"));

        assertThat(result).extracting(OpsBookingPayment::bookingId).containsExactly("b-paid", "b-pending");
        OpsBookingPayment p = result.get(0);
        assertThat(p.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(p.method()).isEqualTo(PaymentMethod.PAYSTACK);
        assertThat(p.cardLast4()).isEqualTo("4081");
        assertThat(p.paidAt()).isNotNull();
        assertThat(p.amountPaid()).isEqualByComparingTo("3600.00");
        assertThat(p.refunded()).isEqualByComparingTo("1200.00");
        assertThat(p.refundPending()).isEqualByComparingTo("600.00");
        assertThat(p.netPaid()).isEqualByComparingTo("1800.00");

        OpsBookingPayment pending = result.get(1);
        assertThat(pending.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(pending.paidAt()).isNull();
        assertThat(pending.amountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(service.forBookings(List.of())).isEmpty();
    }

    /**
     * B-524EA3 on dev: ₦500 paid, ₦507.62 shown refunded. The ₦507.62 was a DUPLICATE_CHARGE refund - a second Paystack
     * charge for the same booking (₦500 plus Paystack's 1.5% fee passed to the card: 500 / 0.985 = 507.62) sent straight
     * back. It is a different charge from the ₦500, so it must not be counted as a refund of it.
     */
    @Test
    void aDuplicateChargeSentBackIsShownApartAndNeverMakesRefundedExceedPaid() {
        PaymentTransaction paid = charge("b-524ea3", "500.00", PayoutStatus.PAID);
        refundRepository.save(Refund.builder().userId("rider-1").bookingId("b-524ea3").paymentTransactionId(paid.getId())
                .amount(new BigDecimal("507.62")).status(RefundStatus.REFUNDED).kind(RefundKind.DUPLICATE_CHARGE).build());

        OpsBookingPayment p = service.forBookings(List.of("b-524ea3")).get(0);

        assertThat(p.amountPaid()).isEqualByComparingTo("500.00");
        assertThat(p.refunded()).isEqualByComparingTo("0");
        assertThat(p.netPaid()).isEqualByComparingTo("500.00");
        assertThat(p.duplicateChargeRefunded()).isEqualByComparingTo("507.62");
    }

    @Test
    void refundedNeverReadsMoreThanPaidWhateverTheRowsSay() {
        PaymentTransaction paid = charge("b-over", "500.00", PayoutStatus.PAID);
        refund("b-over", paid, "500.00", RefundStatus.REFUNDED);
        refund("b-over", paid, "7.62", RefundStatus.REFUNDED);
        refund("b-over", paid, "100.00", RefundStatus.PENDING);

        OpsBookingPayment p = service.forBookings(List.of("b-over")).get(0);

        assertThat(p.refunded()).isEqualByComparingTo("500.00");
        assertThat(p.refundPending()).isEqualByComparingTo("0");
        assertThat(p.netPaid()).isEqualByComparingTo("0");
    }

    private PaymentTransaction charge(String bookingId, String amount, PayoutStatus status) {
        return paymentTransactionRepository.save(PaymentTransaction.builder()
                .bookingId(bookingId).riderId("rider-1").amount(new BigDecimal(amount))
                .gatewayReference(UUID.randomUUID().toString()).cardType("visa").cardLast4("4081").status(status).build());
    }

    private void refund(String bookingId, PaymentTransaction charge, String amount, RefundStatus status) {
        refundRepository.save(Refund.builder().userId("rider-1").bookingId(bookingId).paymentTransactionId(charge.getId())
                .amount(new BigDecimal(amount)).status(status).kind(RefundKind.CANCELLATION).build());
    }
}

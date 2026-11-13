package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.payment.dto.OpsBookingPayment;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.Refund;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Payments and refunds for a page of bookings - two queries however many. Bookings with no payment are left out. */
@Service
@RequiredArgsConstructor
public class OpsBookingPaymentService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;

    @Transactional(readOnly = true)
    public List<OpsBookingPayment> forBookings(Collection<String> bookingIds) {
        List<String> ids = bookingIds.stream().distinct().limit(100).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, List<PaymentTransaction>> payments = paymentTransactionRepository.findByBookingIdIn(ids).stream()
                .collect(Collectors.groupingBy(PaymentTransaction::getBookingId));
        Map<String, List<Refund>> refunds = refundRepository.findByBookingIdIn(ids).stream()
                .collect(Collectors.groupingBy(Refund::getBookingId));
        return ids.stream().filter(payments::containsKey).map(id -> {
            List<PaymentTransaction> charges = payments.get(id);
            List<PaymentTransaction> paid = charges.stream().filter(t -> t.getStatus() == PayoutStatus.PAID).toList();
            PaymentTransaction shown = (paid.isEmpty() ? charges : paid).stream()
                    .max(Comparator.comparing(PaymentTransaction::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElseThrow();
            BigDecimal amountPaid = sum(paid.stream().map(PaymentTransaction::getAmount).toList());
            List<Refund> back = refunds.getOrDefault(id, List.of());
            // A duplicate charge's refund returns a different charge than the one in amountPaid (B-524EA3: ₦507.62 back
            // against ₦500 paid - the duplicate's own amount, Paystack's fee included), so it is kept out of refunded.
            List<Refund> ofThisPayment = back.stream().filter(r -> r.getKind() == RefundKind.CANCELLATION).toList();
            BigDecimal refunded = sum(ofThisPayment.stream().filter(r -> r.getStatus() == RefundStatus.REFUNDED).map(Refund::getAmount).toList())
                    .min(amountPaid);
            BigDecimal pending = sum(ofThisPayment.stream().filter(r -> r.getStatus() == RefundStatus.PENDING).map(Refund::getAmount).toList())
                    .min(amountPaid.subtract(refunded));
            BigDecimal duplicates = sum(back.stream().filter(r -> r.getKind() == RefundKind.DUPLICATE_CHARGE && r.getStatus() == RefundStatus.REFUNDED)
                    .map(Refund::getAmount).toList());
            return new OpsBookingPayment(id, shown.getStatus(), shown.getPaymentMethod(), shown.getCardType(), shown.getCardLast4(),
                    shown.getGatewayReference(), shown.getStatus() == PayoutStatus.PAID ? shown.getUpdatedAt() : null,
                    amountPaid, refunded, pending, amountPaid.subtract(refunded).subtract(pending).max(BigDecimal.ZERO), duplicates);
        }).toList();
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

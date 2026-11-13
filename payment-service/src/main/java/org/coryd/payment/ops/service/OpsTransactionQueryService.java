package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.Person;
import org.coryd.payment.ops.dto.RefundableView;
import org.coryd.payment.ops.dto.TimelineEntry;
import org.coryd.payment.ops.dto.TransactionDetail;
import org.coryd.payment.ops.dto.TransactionFilter;
import org.coryd.payment.ops.dto.TransactionRow;
import org.coryd.payment.ops.dto.TransactionType;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Transactions list and one transaction's detail. */
@Service
@RequiredArgsConstructor
public class OpsTransactionQueryService {

    private final OpsMoneyQueryRepository queryRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FareHoldRepository fareHoldRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyAuditRepository auditRepository;
    private final OpsRefundService refundService;
    private final OpsPayoutService payoutService;
    private final OpsRequestSupport support;

    @Transactional(readOnly = true)
    public PagedResponse<TransactionRow> list(TransactionFilter filter, int page, int size) {
        List<TransactionRow> rows = queryRepository.transactions(filter, page, size);
        long total = queryRepository.countTransactions(filter);
        int totalPages = (int) Math.ceil(total / (double) size);
        return new PagedResponse<>(rows, page, size, total, totalPages, (long) (page + 1) * size >= total);
    }

    /** {all, payment, refund, payout} - every filter but the type. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(TransactionFilter filter) {
        Map<TransactionType, Long> byType = queryRepository.countByType(filter);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", byType.values().stream().mapToLong(Long::longValue).sum());
        byType.forEach((type, count) -> counts.put(type.name().toLowerCase(Locale.ROOT), count));
        return counts;
    }

    @Transactional(readOnly = true)
    public TransactionDetail detail(TransactionType type, String id, OpsActor viewer) {
        TransactionRow row = queryRepository.transaction(type, id)
                .orElseThrow(() -> new ResourceNotFoundException("No such " + type.name().toLowerCase(Locale.ROOT)));
        if (type == TransactionType.PAYOUT) {
            var payout = payoutService.detail(id, viewer);
            return new TransactionDetail(row, null, payout, payout.timeline());
        }
        if (row.bookingId() == null) {
            // A driver's debt repayment, or the refund of what they overpaid on it - no booking around it.
            List<TimelineEntry> timeline = new ArrayList<>();
            timeline.add(new TimelineEntry(row.occurredAt(), type + "_" + row.status(), row.amount(), row.kind(), null));
            return new TransactionDetail(row, null, null, timeline);
        }
        return new TransactionDetail(row, booking(row.bookingId(), viewer), null, timeline(row.bookingId()));
    }

    private TransactionDetail.BookingMoney booking(String bookingId, OpsActor viewer) {
        PaymentTransaction payment = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        TransactionRow paymentRow = payment == null ? null : queryRepository.transaction(TransactionType.PAYMENT, payment.getId()).orElse(null);
        RefundableView refundable = payment == null ? null : refundService.refundable(payment, null);
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        EarningsTransaction earning = earningsTransactionRepository.findByBookingIdIn(List.of(bookingId)).stream().findFirst().orElse(null);
        List<TransactionRow> refunds = queryRepository.transactions(new TransactionFilter(TransactionType.REFUND, null, null, null, null,
                null, bookingId, null, null, null, null, TransactionFilter.Sort.OLDEST), 0, 100);
        List<OpsMoneyRequestView> requests = requestRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(r -> support.view(r, viewer)).toList();
        return new TransactionDetail.BookingMoney(paymentRow, refundable,
                hold == null ? null : new TransactionDetail.Hold(hold.getId(), hold.getDriverId(), hold.getAmount(), hold.isReleased(),
                        hold.isRefunded(), hold.getRefundDue(), hold.getDebtRecovery(), hold.getRefundReason()),
                earning == null ? null : new TransactionDetail.Earning(earning.getRoutePostId(), earning.getGrossFare(),
                        earning.getCommissionAmount(), earning.getNetCredit(), earning.getCreatedAt()),
                refunds, requests);
    }

    /** Everything that happened to a booking's money, oldest first. */
    private List<TimelineEntry> timeline(String bookingId) {
        List<TimelineEntry> timeline = new ArrayList<>();
        paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).ifPresent(p -> {
            timeline.add(new TimelineEntry(p.getCreatedAt(), "PAYMENT_STARTED", p.getAmount(), "Checkout " + p.getGatewayReference(), null));
            if (p.getStatus() != org.coryd.common.enums.PayoutStatus.PENDING) {
                timeline.add(new TimelineEntry(p.getUpdatedAt(), "PAYMENT_" + p.getStatus(), p.getAmount(),
                        p.getCardLast4() == null ? null : (p.getCardType() == null ? "Card" : p.getCardType().trim()) + " •••• " + p.getCardLast4(),
                        null));
            }
        });
        earningsTransactionRepository.findByBookingIdIn(List.of(bookingId)).forEach(e -> timeline.add(new TimelineEntry(e.getCreatedAt(),
                "DRIVER_CREDITED", e.getGrossFare(), "Net to driver " + e.getNetCredit() + " after commission " + e.getCommissionAmount(), null)));
        queryRepository.transactions(new TransactionFilter(TransactionType.REFUND, null, null, null, null, null, bookingId, null, null,
                null, null, TransactionFilter.Sort.OLDEST), 0, 100).forEach(r -> timeline.add(new TimelineEntry(r.occurredAt(),
                "REFUND_" + r.status() + "_" + r.kind(), r.amount(), r.reason(), null)));
        List<OpsMoneyRequest> requests = requestRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);
        for (OpsMoneyRequest r : requests) {
            for (OpsMoneyAudit a : auditRepository.findByRequestIdOrderByCreatedAtAsc(r.getId())) {
                timeline.add(new TimelineEntry(a.getCreatedAt(), a.getAction().name(), a.getAmount(), a.getReason(),
                        new Person(a.getActorId(), a.getActorName())));
            }
        }
        timeline.sort(Comparator.comparing(TimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return timeline;
    }
}

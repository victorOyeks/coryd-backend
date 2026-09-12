package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FareHoldRepository fareHoldRepository;
    private final PaymentGatewayClient gatewayClient;
    private final WalletService walletService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public InitiatePaymentResponse initiate(InitiatePaymentRequest request) {
        PaymentMethod method = request.paymentMethod() == null ? PaymentMethod.PAYSTACK : request.paymentMethod();
        return method == PaymentMethod.WALLET ? payFromWallet(request) : payViaPaystack(request);
    }

    /** Receipt/Pay Now screen's payment method/status. */
    public PaymentSummaryResponse getByBooking(String bookingId) {
        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        return new PaymentSummaryResponse(transaction.getPaymentMethod(), transaction.getStatus());
    }

    /** Upserts the PaymentTransaction/FareHold pair keyed on the DB-level booking_id unique
     * constraint (006-payment-fare-hold-booking-unique.sql), instead of a plain insert or an
     * application-level check-then-insert - the latter is still racy (two near-simultaneous
     * initiate() calls for the same booking could both pass a "does a row exist yet" check before
     * either commits). The database, not this method, is what actually guarantees at most one row
     * per booking now; upsertByBookingId()'s WHERE clause makes the conflict-update a no-op once
     * the row is already PAID, which is how affected==0 is distinguished from a normal
     * reused/updated retry without a second read. The upfront check below is just a fast, friendly
     * rejection for the common case - not the enforcement mechanism. */
    private InitiatePaymentResponse payViaPaystack(InitiatePaymentRequest request) {
        rejectIfAlreadyPaid(request.bookingId());

        String reference = UUID.randomUUID().toString();
        PaymentGatewayClient.GatewayInitResult result =
                gatewayClient.initiate(reference, request.amount(), request.riderEmail(), request.callbackUrl());

        int affected = paymentTransactionRepository.upsertByBookingId(
                UUID.randomUUID().toString(), request.bookingId(), request.riderId(), request.amount(),
                result.gatewayReference(), PayoutStatus.PENDING.name(), PaymentMethod.PAYSTACK.name(),
                PaymentPurpose.BOOKING_PAYMENT.name());
        if (affected == 0) {
            throw new BadRequestException("This booking has already been paid for");
        }

        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(request.bookingId())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but no transaction found for booking " + request.bookingId()));

        fareHoldRepository.insertIfAbsent(UUID.randomUUID().toString(), transaction.getId(),
                request.bookingId(), request.driverId(), request.amount());

        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
    }

    /** No gateway involved at all - the fare is debited from the rider's wallet synchronously, so
     * the booking confirms immediately instead of waiting on a webhook. Note this only closes the
     * PaymentTransaction/FareHold duplication - WalletService.debitForBooking() itself still has no
     * guard against being called twice for the same booking, so a true concurrent double-tap here
     * (not the sequential retry this whole fix targets) could still debit the wallet twice before
     * either upsert below runs; affected==0 in that case means exactly that already happened, and
     * is surfaced loudly rather than swallowed. */
    private InitiatePaymentResponse payFromWallet(InitiatePaymentRequest request) {
        rejectIfAlreadyPaid(request.bookingId());

        String reference = UUID.randomUUID().toString();
        walletService.debitForBooking(request.riderId(), request.bookingId(), request.amount(), reference);

        int affected = paymentTransactionRepository.upsertByBookingId(
                UUID.randomUUID().toString(), request.bookingId(), request.riderId(), request.amount(),
                reference, PayoutStatus.PAID.name(), PaymentMethod.WALLET.name(), PaymentPurpose.BOOKING_PAYMENT.name());
        if (affected == 0) {
            throw new IllegalStateException("Wallet was debited for booking " + request.bookingId()
                    + " but a payment transaction for it was already PAID - likely a concurrent duplicate debit");
        }

        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(request.bookingId())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but no transaction found for booking " + request.bookingId()));

        // Same FareHold as the Paystack path - EarningsService's commission/refund logic
        // doesn't need to know which payment method funded it.
        fareHoldRepository.insertIfAbsent(UUID.randomUUID().toString(), transaction.getId(),
                request.bookingId(), request.driverId(), request.amount());

        kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCEEDED, PaymentSucceededEvent.builder()
                .bookingId(transaction.getBookingId())
                .paymentTransactionId(transaction.getId())
                .amount(transaction.getAmount())
                .paidAt(LocalDateTime.now())
                .build());

        return new InitiatePaymentResponse(null, reference, PayoutStatus.PAID);
    }

    private void rejectIfAlreadyPaid(String bookingId) {
        paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .filter(t -> t.getStatus() == PayoutStatus.PAID)
                .ifPresent(t -> {
                    throw new BadRequestException("This booking has already been paid for");
                });
    }

    @Transactional
    public void handleWebhookSuccess(String gatewayReference) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown payment reference"));

        transaction.setStatus(PayoutStatus.PAID);
        paymentTransactionRepository.save(transaction);

        if (transaction.getPurpose() == PaymentPurpose.WALLET_TOPUP) {
            walletService.creditFromTopUp(transaction);
            return;
        }

        kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCEEDED, PaymentSucceededEvent.builder()
                .bookingId(transaction.getBookingId())
                .paymentTransactionId(transaction.getId())
                .amount(transaction.getAmount())
                .paidAt(LocalDateTime.now())
                .build());
    }

    @Transactional
    public void handleWebhookFailure(String gatewayReference, String reason) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown payment reference"));

        transaction.setStatus(PayoutStatus.FAILED);
        paymentTransactionRepository.save(transaction);

        if (transaction.getPurpose() == PaymentPurpose.WALLET_TOPUP) {
            // Nothing was ever held for a top-up - the rider just sees it failed.
            return;
        }

        kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, PaymentFailedEvent.builder()
                .bookingId(transaction.getBookingId())
                .riderId(transaction.getRiderId())
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build());
    }
}

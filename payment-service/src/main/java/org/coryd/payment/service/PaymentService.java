package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.WalletTransactionRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FareHoldRepository fareHoldRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentGatewayClient gatewayClient;
    private final WalletService walletService;
    private final TripServiceClient tripServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CheckoutEmailResolver checkoutEmailResolver;
    private final DriverSplitResolver driverSplitResolver;

    /** Amount, rider and driver all come from trip-service's booking - the request only says which
     * booking and how to pay, so a client can't pay less, pay from someone else's wallet, or route
     * the fare to another driver. */
    @Transactional
    public InitiatePaymentResponse initiate(InitiatePaymentRequest request, String callerId) {
        TripServiceClient.BookingPaymentView booking = tripServiceClient.fetchPaymentView(request.bookingId());
        if (!callerId.equals(booking.riderId())) {
            throw new UnauthorizedException("Only this booking's rider can pay for it");
        }
        rejectIfAlreadyPaid(booking.bookingId());
        if (booking.status() != BookingStatus.HELD) {
            throw new BadRequestException("This booking is no longer awaiting payment - its seat hold has ended. Please book again.");
        }
        PaymentMethod method = request.paymentMethod() == null ? PaymentMethod.PAYSTACK : request.paymentMethod();
        return method == PaymentMethod.WALLET ? payFromWallet(booking) : payViaPaystack(booking, request);
    }

    /** Receipt/Pay Now screen's payment method/status. */
    public PaymentSummaryResponse getByBooking(String bookingId) {
        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        return new PaymentSummaryResponse(transaction.getPaymentMethod(), transaction.getStatus());
    }

    /** Upserts on the booking_id unique index instead of a plain insert - the DB, not this method,
     * guarantees at most one row per booking. A retry while a checkout is still open gets that same
     * checkout back, so every charge lands on one reference. */
    private InitiatePaymentResponse payViaPaystack(TripServiceClient.BookingPaymentView booking, InitiatePaymentRequest request) {
        PaymentTransaction open = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                .filter(t -> t.getStatus() == PayoutStatus.PENDING && t.getPaymentMethod() == PaymentMethod.PAYSTACK)
                .filter(t -> t.getAuthorizationUrl() != null && t.getAmount().compareTo(booking.fareTotal()) == 0)
                .orElse(null);
        if (open != null) {
            return new InitiatePaymentResponse(open.getAuthorizationUrl(), open.getGatewayReference(), PayoutStatus.PENDING);
        }

        String reference = UUID.randomUUID().toString();
        DriverSplitResolver.Split split = driverSplitResolver.resolve(booking.driverId(), booking.fareTotal());
        PaymentGatewayClient.GatewayInitResult result = gatewayClient.initiateSplit(reference, booking.fareTotal(),
                checkoutEmailResolver.resolve(booking.riderId(), request.riderEmail()), request.callbackUrl(),
                Map.of("bookingId", booking.bookingId(), "purpose", PaymentPurpose.BOOKING_PAYMENT.name()),
                split.subaccountCode(), split.flatChargeKobo());

        int affected = paymentTransactionRepository.upsertByBookingId(
                UUID.randomUUID().toString(), booking.bookingId(), booking.riderId(), booking.fareTotal(),
                result.gatewayReference(), result.authorizationUrl(), PayoutStatus.PENDING.name(),
                PaymentMethod.PAYSTACK.name(), PaymentPurpose.BOOKING_PAYMENT.name());
        if (affected == 0) {
            throw new BadRequestException("This booking has already been paid for");
        }

        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but no transaction found for booking " + booking.bookingId()));

        fareHoldRepository.insertIfAbsent(UUID.randomUUID().toString(), transaction.getId(),
                booking.bookingId(), booking.driverId(), booking.fareTotal());

        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
    }

    /** No gateway involved - the fare is debited from the wallet synchronously, so the booking
     * confirms immediately instead of waiting on a webhook. A concurrent double-tap loses on the
     * upsert (affected==0) and its debit rolls back with it. */
    private InitiatePaymentResponse payFromWallet(TripServiceClient.BookingPaymentView booking) {
        String reference = UUID.randomUUID().toString();
        walletService.debitForBooking(booking.riderId(), booking.bookingId(), booking.fareTotal(), reference);

        int affected = paymentTransactionRepository.upsertByBookingId(
                UUID.randomUUID().toString(), booking.bookingId(), booking.riderId(), booking.fareTotal(),
                reference, null, PayoutStatus.PAID.name(), PaymentMethod.WALLET.name(), PaymentPurpose.BOOKING_PAYMENT.name());
        if (affected == 0) {
            throw new BadRequestException("This booking has already been paid for");
        }

        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but no transaction found for booking " + booking.bookingId()));

        // Same FareHold as the Paystack path - EarningsService's commission/refund logic
        // doesn't need to know which payment method funded it.
        fareHoldRepository.insertIfAbsent(UUID.randomUUID().toString(), transaction.getId(),
                booking.bookingId(), booking.driverId(), booking.fareTotal());

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

    /** Paystack resends webhooks - only the first delivery moves the transaction to PAID, so a
     * repeat can't credit a top-up twice or re-confirm a booking. */
    @Transactional
    public void handleWebhookSuccess(String gatewayReference, String metadataBookingId, Long amountKobo) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference)
                .orElseGet(() -> adoptOrphanCharge(gatewayReference, metadataBookingId, amountKobo));
        if (transaction == null) {
            return;
        }
        if (paymentTransactionRepository.transitionStatus(transaction.getId(),
                List.of(PayoutStatus.PENDING, PayoutStatus.FAILED), PayoutStatus.PAID) == 0) {
            log.info("Ignoring repeat charge.success for reference {}", gatewayReference);
            return;
        }
        transaction.setStatus(PayoutStatus.PAID);

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

    /** A charge on a reference we no longer hold - the booking's row moved on to a newer checkout or
     * a wallet payment before the rider finished an older checkout. The money is real either way:
     * if the booking is still unpaid this charge pays it, otherwise it goes back to the rider's wallet. */
    private PaymentTransaction adoptOrphanCharge(String reference, String bookingId, Long amountKobo) {
        PaymentTransaction current = bookingId == null ? null
                : paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (current == null) {
            log.error("RECONCILE: charge.success for unknown reference {} (booking {}) - rider was charged but nothing matches it",
                    reference, bookingId);
            return null;
        }
        if (current.getStatus() != PayoutStatus.PAID) {
            paymentTransactionRepository.adoptReference(current.getId(), reference);
            current.setGatewayReference(reference);
            return current;
        }

        String creditReference = "Duplicate payment " + reference;
        if (walletTransactionRepository.existsByReference(creditReference)) {
            return null;
        }
        BigDecimal amount = amountKobo == null ? current.getAmount() : BigDecimal.valueOf(amountKobo).movePointLeft(2);
        walletService.creditRefund(current.getRiderId(), bookingId, amount, creditReference);
        log.warn("Booking {} was already paid - duplicate Paystack charge {} of {} credited to rider {}'s wallet",
                bookingId, reference, amount, current.getRiderId());
        return null;
    }

    /** Only a still-pending attempt can fail - a late failure for a checkout that was superseded or
     * already paid must not release a confirmed booking's seat. */
    @Transactional
    public void handleWebhookFailure(String gatewayReference, String reason) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference).orElse(null);
        if (transaction == null) {
            log.info("Ignoring charge.failed for superseded reference {}", gatewayReference);
            return;
        }
        if (paymentTransactionRepository.transitionStatus(transaction.getId(), List.of(PayoutStatus.PENDING), PayoutStatus.FAILED) == 0) {
            return;
        }

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

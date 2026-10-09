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
import org.coryd.payment.dto.CardAuthorization;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.SavedCard;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every fare is a card charge split at charge time - the driver's share goes to their subaccount and
 * never sits with us. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FareHoldRepository fareHoldRepository;
    private final PaymentGatewayClient gatewayClient;
    private final TripServiceClient tripServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CheckoutEmailResolver checkoutEmailResolver;
    private final DriverSplitResolver driverSplitResolver;
    private final SavedCardService savedCardService;
    private final EarningsService earningsService;

    private static final BigDecimal MIN_REPAYMENT = new BigDecimal("100.00");

    /** Amount, rider and driver all come from trip-service's booking - the request only says which
     * booking and which card, so a client can't pay less, use someone else's card, or route the fare
     * to another driver. */
    @Transactional
    public InitiatePaymentResponse initiate(InitiatePaymentRequest request, String callerId) {
        if (request.paymentMethod() == PaymentMethod.WALLET) {
            throw new BadRequestException("Paying from a wallet is no longer available - please pay by card");
        }
        TripServiceClient.BookingPaymentView booking = tripServiceClient.fetchPaymentView(request.bookingId());
        if (!callerId.equals(booking.riderId())) {
            throw new UnauthorizedException("Only this booking's rider can pay for it");
        }
        rejectIfAlreadyPaid(booking.bookingId());
        if (booking.status() != BookingStatus.HELD) {
            throw new BadRequestException("This booking is no longer awaiting payment - its seat hold has ended. Please book again.");
        }
        if (request.savedCardId() != null && !request.savedCardId().isBlank()) {
            return payWithSavedCard(booking, request);
        }
        return payViaCheckout(booking, request);
    }

    /** A driver paying back what they owe, by card, to CoRyd with no split. amount null pays it all; a part
     * payment is at least MIN_REPAYMENT (or everything, if less is owed). */
    @Transactional
    public InitiatePaymentResponse initiateRepayment(String driverId, String callerId, BigDecimal amount, String email, String callbackUrl) {
        if (!driverId.equals(callerId)) {
            throw new UnauthorizedException("Only this driver can pay back what they owe");
        }
        BigDecimal debt = earningsService.amountOwed(driverId);
        if (debt.signum() <= 0) {
            throw new BadRequestException("You don't owe anything");
        }
        BigDecimal owed = amount == null ? debt : amount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (owed.compareTo(debt) > 0) {
            throw new BadRequestException("You only owe ₦" + debt.toPlainString());
        }
        if (owed.compareTo(debt) < 0 && owed.compareTo(MIN_REPAYMENT) < 0) {
            throw new BadRequestException("The least you can pay is ₦" + MIN_REPAYMENT.toPlainString());
        }
        String reference = UUID.randomUUID().toString();
        PaymentGatewayClient.GatewayInitResult result = gatewayClient.initiate(reference, owed,
                checkoutEmailResolver.resolve(driverId, email), callbackUrl,
                Map.of("driverId", driverId, "purpose", PaymentPurpose.DEBT_REPAYMENT.name()));
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .riderId(driverId)
                .amount(owed)
                .gatewayReference(result.gatewayReference())
                .authorizationUrl(result.authorizationUrl())
                .purpose(PaymentPurpose.DEBT_REPAYMENT)
                .build());
        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
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
    private InitiatePaymentResponse payViaCheckout(TripServiceClient.BookingPaymentView booking, InitiatePaymentRequest request) {
        PaymentTransaction open = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                .filter(t -> t.getStatus() == PayoutStatus.PENDING)
                .filter(t -> t.getAuthorizationUrl() != null && t.getAmount().compareTo(booking.fareTotal()) == 0)
                .orElse(null);
        if (open != null) {
            return new InitiatePaymentResponse(open.getAuthorizationUrl(), open.getGatewayReference(), PayoutStatus.PENDING);
        }

        String reference = UUID.randomUUID().toString();
        DriverSplitResolver.Split split = driverSplitResolver.resolve(booking.driverId(), booking.fareTotal());
        PaymentGatewayClient.GatewayInitResult result = gatewayClient.initiateSplit(reference, booking.fareTotal(),
                checkoutEmailResolver.resolve(booking.riderId(), request.riderEmail()), request.callbackUrl(),
                metadata(booking), split.subaccountCode(), split.flatChargeKobo());

        record(booking, result.gatewayReference(), result.authorizationUrl(), split);
        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
    }

    /**
     * One tap, no redirect. A card the bank won't charge without the rider (OTP, 3-D Secure, declined)
     * falls back to a normal checkout, so the rider is never stuck. Paystack's own charge.success for
     * this reference arrives later and is ignored as a repeat.
     */
    private InitiatePaymentResponse payWithSavedCard(TripServiceClient.BookingPaymentView booking, InitiatePaymentRequest request) {
        SavedCard card = savedCardService.owned(booking.riderId(), request.savedCardId());
        String reference = UUID.randomUUID().toString();
        DriverSplitResolver.Split split = driverSplitResolver.resolve(booking.driverId(), booking.fareTotal());
        record(booking, reference, null, split);

        PaymentGatewayClient.ChargeResult result;
        try {
            result = gatewayClient.chargeAuthorization(reference, booking.fareTotal(), card.getEmail(),
                    card.getAuthorizationCode(), metadata(booking), split.subaccountCode(), split.flatChargeKobo());
        } catch (Exception e) {
            // If Paystack did charge, its webhook still lands on this reference and is adopted.
            log.warn("Saved-card charge for booking {} did not complete, falling back to checkout: {}", booking.bookingId(), e.getMessage());
            return payViaCheckout(booking, request);
        }

        if (result.success()) {
            handleWebhookSuccess(reference, booking.bookingId(), booking.fareTotal().movePointRight(2).longValueExact(), null);
            return new InitiatePaymentResponse(null, reference, PayoutStatus.PAID);
        }
        if (result.authorizationUrl() != null) {
            paymentTransactionRepository.upsertByBookingId(UUID.randomUUID().toString(), booking.bookingId(), booking.riderId(),
                    booking.fareTotal(), reference, result.authorizationUrl(), PayoutStatus.PENDING.name(),
                    PaymentMethod.PAYSTACK.name(), PaymentPurpose.BOOKING_PAYMENT.name());
            return new InitiatePaymentResponse(result.authorizationUrl(), reference, PayoutStatus.PENDING);
        }
        log.info("Saved card declined for booking {} ({}), falling back to checkout", booking.bookingId(), result.gatewayResponse());
        return payViaCheckout(booking, request);
    }

    private void record(TripServiceClient.BookingPaymentView booking, String reference, String authorizationUrl,
                        DriverSplitResolver.Split split) {
        int affected = paymentTransactionRepository.upsertByBookingId(
                UUID.randomUUID().toString(), booking.bookingId(), booking.riderId(), booking.fareTotal(),
                reference, authorizationUrl, PayoutStatus.PENDING.name(),
                PaymentMethod.PAYSTACK.name(), PaymentPurpose.BOOKING_PAYMENT.name());
        if (affected == 0) {
            throw new BadRequestException("This booking has already been paid for");
        }
        PaymentTransaction transaction = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but no transaction found for booking " + booking.bookingId()));
        fareHoldRepository.insertIfAbsent(UUID.randomUUID().toString(), transaction.getId(),
                booking.bookingId(), booking.driverId(), booking.fareTotal(), split.debtRecovery());
    }

    private static Map<String, String> metadata(TripServiceClient.BookingPaymentView booking) {
        return Map.of("bookingId", booking.bookingId(), "purpose", PaymentPurpose.BOOKING_PAYMENT.name());
    }

    private void rejectIfAlreadyPaid(String bookingId) {
        paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .filter(t -> t.getStatus() == PayoutStatus.PAID)
                .ifPresent(t -> {
                    throw new BadRequestException("This booking has already been paid for");
                });
    }

    /** Paystack resends webhooks - only the first delivery moves the transaction to PAID, so a repeat
     * can't re-confirm a booking. The card is remembered on every delivery; doing it twice is harmless. */
    @Transactional
    public void handleWebhookSuccess(String gatewayReference, String metadataBookingId, Long amountKobo, CardAuthorization card) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference)
                .orElseGet(() -> adoptOrphanCharge(gatewayReference, metadataBookingId, amountKobo));
        if (transaction == null) {
            return;
        }
        rememberCard(transaction.getRiderId(), card);
        if (paymentTransactionRepository.transitionStatus(transaction.getId(),
                List.of(PayoutStatus.PENDING, PayoutStatus.FAILED), PayoutStatus.PAID) == 0) {
            log.info("Ignoring repeat charge.success for reference {}", gatewayReference);
            return;
        }
        transaction.setStatus(PayoutStatus.PAID);

        if (transaction.getPurpose() == PaymentPurpose.DEBT_REPAYMENT) {
            BigDecimal excess = earningsService.applyRepayment(transaction.getRiderId(), transaction.getAmount(), gatewayReference);
            if (excess.signum() > 0 && !gatewayClient.refund(gatewayReference, excess, "Paid more than was owed")) {
                log.error("RECONCILE: driver {} overpaid {} on {} and it could not be refunded", transaction.getRiderId(), excess, gatewayReference);
            }
            return;
        }

        kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCEEDED, PaymentSucceededEvent.builder()
                .bookingId(transaction.getBookingId())
                .paymentTransactionId(transaction.getId())
                .amount(transaction.getAmount())
                .paidAt(LocalDateTime.now())
                .build());
    }

    private void rememberCard(String riderId, CardAuthorization card) {
        if (card == null) {
            return;
        }
        try {
            savedCardService.remember(riderId, card);
        } catch (Exception e) {
            log.warn("Could not save the card used by rider {}: {}", riderId, e.getMessage());
        }
    }

    /** A charge on a reference we no longer hold - the booking moved on to a newer checkout before the
     * rider finished an older one. If the booking is still unpaid this charge pays it; otherwise it was
     * paid twice, and the extra charge goes straight back to the card. */
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

        BigDecimal amount = amountKobo == null ? current.getAmount() : BigDecimal.valueOf(amountKobo).movePointLeft(2);
        // A resent webhook asks again; Paystack refuses to refund the same charge twice.
        if (gatewayClient.refund(reference, null, "Duplicate payment for a booking already paid")) {
            log.warn("Booking {} was already paid - duplicate charge {} of {} refunded to rider {}'s card",
                    bookingId, reference, amount, current.getRiderId());
            // The duplicate was split too, so the driver was paid for it as well.
            fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).ifPresent(hold ->
                    earningsService.chargeRefundToDriver(hold.getDriverId(), bookingId, amount, hold.getDebtRecovery()));
        } else {
            log.error("RECONCILE: duplicate charge {} of {} on booking {} could not be refunded (it may already have been)",
                    reference, amount, bookingId);
        }
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
        if (paymentTransactionRepository.transitionStatus(transaction.getId(), List.of(PayoutStatus.PENDING), PayoutStatus.FAILED) == 0
                || transaction.getPurpose() == PaymentPurpose.DEBT_REPAYMENT) {
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

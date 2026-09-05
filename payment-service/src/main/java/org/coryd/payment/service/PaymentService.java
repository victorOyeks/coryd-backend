package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.entity.FareHold;
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

    /** Receipt screen's payment method/status - every confirmed booking has exactly one of these. */
    public PaymentSummaryResponse getByBooking(String bookingId) {
        PaymentTransaction transaction = paymentTransactionRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        return new PaymentSummaryResponse(transaction.getPaymentMethod(), transaction.getStatus());
    }

    private InitiatePaymentResponse payViaPaystack(InitiatePaymentRequest request) {
        String reference = UUID.randomUUID().toString();

        PaymentGatewayClient.GatewayInitResult result =
                gatewayClient.initiate(reference, request.amount(), request.riderEmail(), request.callbackUrl());

        PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                .bookingId(request.bookingId())
                .riderId(request.riderId())
                .amount(request.amount())
                .gatewayReference(result.gatewayReference())
                .status(PayoutStatus.PENDING)
                .paymentMethod(PaymentMethod.PAYSTACK)
                .purpose(PaymentPurpose.BOOKING_PAYMENT)
                .build());

        fareHoldRepository.save(FareHold.builder()
                .paymentTransactionId(transaction.getId())
                .bookingId(request.bookingId())
                .driverId(request.driverId())
                .amount(request.amount())
                .build());

        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
    }

    /** No gateway involved at all - the fare is debited from the rider's wallet synchronously, so the booking confirms immediately instead of waiting on a webhook. */
    private InitiatePaymentResponse payFromWallet(InitiatePaymentRequest request) {
        String reference = UUID.randomUUID().toString();
        walletService.debitForBooking(request.riderId(), request.bookingId(), request.amount(), reference);

        PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                .bookingId(request.bookingId())
                .riderId(request.riderId())
                .amount(request.amount())
                .gatewayReference(reference)
                .status(PayoutStatus.PAID)
                .paymentMethod(PaymentMethod.WALLET)
                .purpose(PaymentPurpose.BOOKING_PAYMENT)
                .build());

        // Same FareHold as the Paystack path - EarningsService's commission/refund logic
        // doesn't need to know which payment method funded it.
        fareHoldRepository.save(FareHold.builder()
                .paymentTransactionId(transaction.getId())
                .bookingId(request.bookingId())
                .driverId(request.driverId())
                .amount(request.amount())
                .build());

        kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCEEDED, PaymentSucceededEvent.builder()
                .bookingId(transaction.getBookingId())
                .paymentTransactionId(transaction.getId())
                .amount(transaction.getAmount())
                .paidAt(LocalDateTime.now())
                .build());

        return new InitiatePaymentResponse(null, reference, PayoutStatus.PAID);
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

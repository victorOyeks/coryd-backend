package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.events.PaymentFailedEvent;
import org.coryd.common.kafka.events.PaymentSucceededEvent;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public InitiatePaymentResponse initiate(InitiatePaymentRequest request) {
        String reference = "CORYD-" + UUID.randomUUID();

        PaymentGatewayClient.GatewayInitResult result =
                gatewayClient.initiate(reference, request.amount(), request.riderEmail());

        PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                .bookingId(request.bookingId())
                .riderId(request.riderId())
                .amount(request.amount())
                .gatewayReference(result.gatewayReference())
                .status(PayoutStatus.PENDING)
                .build());

        fareHoldRepository.save(FareHold.builder()
                .paymentTransactionId(transaction.getId())
                .bookingId(request.bookingId())
                .driverId(request.driverId())
                .amount(request.amount())
                .build());

        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference());
    }

    @Transactional
    public void handleWebhookSuccess(String gatewayReference) {
        PaymentTransaction transaction = paymentTransactionRepository.findByGatewayReference(gatewayReference)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown payment reference"));

        transaction.setStatus(PayoutStatus.PAID);
        paymentTransactionRepository.save(transaction);

        kafkaTemplate.send("coryd-payment-succeeded", PaymentSucceededEvent.builder()
                .bookingId(transaction.getBookingId().toString())
                .paymentTransactionId(transaction.getId().toString())
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

        kafkaTemplate.send("coryd-payment-failed", PaymentFailedEvent.builder()
                .bookingId(transaction.getBookingId().toString())
                .reason(reason)
                .failedAt(LocalDateTime.now())
                .build());
    }
}

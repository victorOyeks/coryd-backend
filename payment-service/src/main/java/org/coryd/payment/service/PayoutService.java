package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.dto.PayoutReceiptResponse;
import org.coryd.payment.entity.Payout;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.coryd.payment.repository.PayoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private static final String WITHDRAWAL_REASON = "CoRyd driver withdrawal";

    private final PayoutRepository payoutRepository;
    private final PayoutRecipientRepository payoutRecipientRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final PaystackGatewayClient paystackGatewayClient;

    /** The Withdraw Funds screen's submit action - debits eagerly (atomic, race-safe) before the gateway call, then reverses on any failure since the money never actually left. */
    @Transactional
    public Payout requestWithdrawal(String driverId, String callerId, BigDecimal amount) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can withdraw from their own wallet");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Withdrawal amount must be greater than zero");
        }

        PayoutRecipient recipient = payoutRecipientRepository.findByDriverId(driverId)
                .filter(r -> r.getGatewayRecipientCode() != null)
                .orElseThrow(() -> new BadRequestException("Add and verify a bank account before withdrawing"));

        int updated = earningsAccountRepository.debitBalance(driverId, amount);
        if (updated == 0) {
            throw new BadRequestException("Insufficient wallet balance");
        }

        String reference = UUID.randomUUID().toString();
        Payout payout = payoutRepository.save(Payout.builder()
                .driverId(driverId)
                .amount(amount)
                .gatewayReference(reference)
                .status(PayoutStatus.PENDING)
                .build());

        PaystackGatewayClient.TransferResult result;
        try {
            result = paystackGatewayClient.initiateTransfer(reference, amount, recipient.getGatewayRecipientCode(), WITHDRAWAL_REASON);
        } catch (Exception e) {
            // Only a failure of the gateway call itself is safe to reverse - once Paystack has
            // accepted the transfer, the money is already committed to move, so a failure in our
            // own bookkeeping after that point must never be treated as a transfer failure (that
            // would refund the driver's balance while Paystack still sends the real money).
            log.error("Transfer initiation failed for payout {} (driver {}): {}", payout.getId(), driverId, e.getMessage());
            failAndReverse(payout, "Withdrawal could not be initiated: " + e.getMessage());
            return payout;
        }

        payout.setStatus("success".equalsIgnoreCase(result.status()) ? PayoutStatus.PAID : PayoutStatus.PROCESSING);
        payoutRepository.save(payout);
        return payout;
    }

    /** transfer.success webhook. */
    @Transactional
    public void handleTransferSuccess(String gatewayReference) {
        payoutRepository.findByGatewayReference(gatewayReference).ifPresentOrElse(payout -> {
            if (isTerminal(payout)) {
                return;
            }
            payout.setStatus(PayoutStatus.PAID);
            payoutRepository.save(payout);
        }, () -> log.warn("No payout found for gatewayReference {} on transfer.success", gatewayReference));
    }

    /** transfer.failed / transfer.reversed webhook - guarded against double-crediting if the synchronous path already reversed this same payout. */
    @Transactional
    public void handleTransferFailure(String gatewayReference, String reason) {
        payoutRepository.findByGatewayReference(gatewayReference).ifPresentOrElse(payout -> {
            if (isTerminal(payout)) {
                return;
            }
            failAndReverse(payout, reason);
        }, () -> log.warn("No payout found for gatewayReference {} on transfer failure", gatewayReference));
    }

    private void failAndReverse(Payout payout, String reason) {
        payout.setStatus(PayoutStatus.FAILED);
        payout.setFailureReason(reason);
        payoutRepository.save(payout);
        earningsAccountRepository.creditBalance(payout.getDriverId(), payout.getAmount());
    }

    private boolean isTerminal(Payout payout) {
        return payout.getStatus() == PayoutStatus.PAID || payout.getStatus() == PayoutStatus.FAILED;
    }

    /** The Withdrawal Receipt screen - only the payout's own driver can view it. */
    public PayoutReceiptResponse getReceipt(String payoutId, String callerId) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout not found"));
        if (!callerId.equals(payout.getDriverId())) {
            throw new UnauthorizedException("Only this payout's driver can view its receipt");
        }
        PayoutRecipient recipient = payoutRecipientRepository.findByDriverId(payout.getDriverId()).orElse(null);

        return new PayoutReceiptResponse(
                payout.getId(), payout.getStatus(), payout.getAmount(), payout.getCreatedAt(),
                recipient == null ? null : recipient.getBankName(),
                recipient == null ? null : maskAccountNumber(recipient.getAccountNumber()),
                receiptReference(payout)
        );
    }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return accountNumber;
        }
        return accountNumber.substring(0, 2) + "****" + accountNumber.substring(accountNumber.length() - 2);
    }

    private static String receiptReference(Payout payout) {
        String datePart = LocalDate.ofInstant(payout.getCreatedAt(), ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String idSuffix = payout.getId().replace("-", "").toUpperCase();
        return "CRY-" + datePart + "-" + idSuffix.substring(0, Math.min(6, idSuffix.length()));
    }
}

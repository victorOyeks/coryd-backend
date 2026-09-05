package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.enums.WalletEntryType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.WalletRepository;
import org.coryd.payment.repository.WalletTransactionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** The rider's internal balance - debited synchronously for "pay from wallet" bookings, topped up via the same Paystack redirect used for direct fare payment. */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentGatewayClient gatewayClient;

    @Transactional
    public Wallet getOrCreateWallet(String userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    walletRepository.insertIfAbsent(UUID.randomUUID().toString(), userId);
                    return walletRepository.findByUserId(userId)
                            .orElseThrow(() -> new IllegalStateException("Wallet insert-if-absent left no row for userId=" + userId));
                });
    }

    // @Transactional here isn't decoration - getOrCreateWallet() calls a @Modifying repository
    // query (insertIfAbsent), which requires an already-active transaction to execute at all.
    // getOrCreateWallet()'s OWN @Transactional doesn't provide that when called from here: it's
    // a same-class call (self-invocation), which bypasses Spring's proxy - the annotation is
    // silently ignored, and Hibernate throws TransactionRequiredException with no transaction
    // anywhere in the call chain. Annotating the actual entry point (invoked through the real
    // proxy, from WalletController) fixes it for the same reason it never mattered before this
    // method existed: walletRepository.save() always got its own transaction from Spring Data's
    // CRUD-method wrapping regardless of the caller, but a custom @Modifying query doesn't.
    @Transactional
    public BigDecimal getBalance(String userId) {
        return getOrCreateWallet(userId).getAvailableBalance();
    }

    @Transactional
    public PagedResponse<WalletTransaction> getTransactions(String userId, Pageable pageable) {
        Wallet wallet = getOrCreateWallet(userId);
        return PagedResponse.from(walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable));
    }

    @Transactional
    public InitiatePaymentResponse initiateTopUp(String userId, BigDecimal amount, String email, String callbackUrl) {
        String reference = UUID.randomUUID().toString();
        PaymentGatewayClient.GatewayInitResult result = gatewayClient.initiate(reference, amount, email, callbackUrl);

        paymentTransactionRepository.save(PaymentTransaction.builder()
                .riderId(userId)
                .amount(amount)
                .gatewayReference(result.gatewayReference())
                .status(PayoutStatus.PENDING)
                .paymentMethod(PaymentMethod.PAYSTACK)
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .build());

        return new InitiatePaymentResponse(result.authorizationUrl(), result.gatewayReference(), PayoutStatus.PENDING);
    }

    /** Called from the Paystack webhook on charge.success for a WALLET_TOPUP transaction. */
    @Transactional
    public void creditFromTopUp(PaymentTransaction transaction) {
        Wallet wallet = getOrCreateWallet(transaction.getRiderId());
        wallet.setAvailableBalance(wallet.getAvailableBalance().add(transaction.getAmount()));
        walletRepository.save(wallet);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .entryType(WalletEntryType.CREDIT)
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .amount(transaction.getAmount())
                .balanceAfter(wallet.getAvailableBalance())
                .reference(transaction.getGatewayReference())
                .build());
    }

    /** Debits synchronously - the "pay from wallet" alternative to a Paystack redirect. */
    @Transactional
    public void debitForBooking(String userId, String bookingId, BigDecimal amount, String reference) {
        Wallet wallet = getOrCreateWallet(userId);
        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient wallet balance");
        }
        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        walletRepository.save(wallet);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .entryType(WalletEntryType.DEBIT)
                .purpose(PaymentPurpose.BOOKING_PAYMENT)
                .amount(amount)
                .balanceAfter(wallet.getAvailableBalance())
                .reference(reference)
                .bookingId(bookingId)
                .build());
    }

    /** Only ever called for bookings that were themselves paid from the wallet - see EarningsService.refund. */
    @Transactional
    public void creditRefund(String userId, String bookingId, BigDecimal amount, String reason) {
        Wallet wallet = getOrCreateWallet(userId);
        wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
        walletRepository.save(wallet);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .entryType(WalletEntryType.CREDIT)
                .purpose(PaymentPurpose.REFUND)
                .amount(amount)
                .balanceAfter(wallet.getAvailableBalance())
                .reference(reason)
                .bookingId(bookingId)
                .build());
    }
}

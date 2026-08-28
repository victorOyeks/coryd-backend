package org.coryd.payment.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.WalletEntryType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
import org.coryd.payment.repository.WalletRepository;
import org.coryd.payment.repository.WalletTransactionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * OPS_ADMIN-only manual credit/debit of any user's wallet, for testing flows that would
 * otherwise need a real Paystack top-up (e.g. QA funding a test rider before a booking).
 * Writes a real WalletTransaction row like every other wallet movement (purpose
 * DEV_ADJUSTMENT) rather than silently overwriting the balance - the ledger stays honest.
 *
 * Never registered unless coryd.dev-tools.enabled is true (application-dev.yml only).
 * Deliberately NOT under /dev/** - that prefix is internal-service-only in this module
 * (InternalOnlyPaths.PAYMENT_SERVICE), gated by the shared internal API key, which an
 * ops admin testing by hand from Postman/Swagger doesn't have. This sits under /wallets/dev/**
 * instead, behind the normal JWT check plus the OPS_ADMIN role check below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class DevWalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    @Transactional
    public Wallet fund(String userId, UserRole callerRole, BigDecimal amount, String reason) {
        requireOpsAdmin(callerRole);
        Wallet wallet = getOrCreateWallet(userId);
        wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
        walletRepository.save(wallet);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .entryType(WalletEntryType.CREDIT)
                .purpose(PaymentPurpose.DEV_ADJUSTMENT)
                .amount(amount)
                .balanceAfter(wallet.getAvailableBalance())
                .reference(reason)
                .build());
        log.warn("DEV wallet fund: userId={} amount={} reason={}", userId, amount, reason);
        return wallet;
    }

    @Transactional
    public Wallet deduct(String userId, UserRole callerRole, BigDecimal amount, String reason) {
        requireOpsAdmin(callerRole);
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for this user"));
        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient wallet balance to deduct this amount");
        }
        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        walletRepository.save(wallet);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .entryType(WalletEntryType.DEBIT)
                .purpose(PaymentPurpose.DEV_ADJUSTMENT)
                .amount(amount)
                .balanceAfter(wallet.getAvailableBalance())
                .reference(reason)
                .build());
        log.warn("DEV wallet deduct: userId={} amount={} reason={}", userId, amount, reason);
        return wallet;
    }

    private Wallet getOrCreateWallet(String userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(Wallet.builder().userId(userId).build()));
    }

    private void requireOpsAdmin(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can adjust a wallet for testing");
        }
    }
}

package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.PayoutRecipientRequest;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.PayoutRecipientRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayoutRecipientService {

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final PaystackGatewayClient paystackGatewayClient;
    private final CommissionProperties commissionProperties;
    private final ResolvedAccountCache resolvedAccountCache;


    public List<PaystackGatewayClient.BankOption> listBanks() {
        return paystackGatewayClient.listBanks();
    }

    /** "Confirm this is you" step before saving - resolves against the bank, doesn't touch anything yet.
     * The answer is kept briefly so saving this account straight after doesn't ask Paystack again. */
    public PaystackGatewayClient.ResolvedAccount resolve(String callerId, String accountNumber, String bankCode) {
        PaystackGatewayClient.ResolvedAccount account = paystackGatewayClient.resolveAccountNumber(accountNumber, bankCode);
        resolvedAccountCache.put(callerId, bankCode, accountNumber, account);
        return account;
    }

    /** Payout Setup screen's saved-accounts list - bank details, so only the driver themselves or ops. */
    public List<PayoutRecipient> list(String driverId, String callerId, UserRole callerRole) {
        if (!driverId.equals(callerId) && callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("You can only view your own payout accounts");
        }
        return payoutRecipientRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
    }

    /** Adds a new saved bank account - re-resolves server-side (never trusts a client-supplied
     * account name) and creates the Paystack subaccount fares are split to. The first account a
     * driver ever saves becomes their default automatically; later ones are added as non-default
     * until explicitly selected. */
    @Transactional
    public PayoutRecipient add(String driverId, String callerId, PayoutRecipientRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can add their own payout bank account");
        }
        PaystackGatewayClient.ResolvedAccount resolved = resolvedAccountCache.get(driverId, request.bankCode(), request.accountNumber())
                .orElseGet(() -> paystackGatewayClient.resolveAccountNumber(request.accountNumber(), request.bankCode()));
        PaystackGatewayClient.SubaccountResult subaccount = paystackGatewayClient.createSubaccount(
                resolved.accountName(), request.accountNumber(), request.bankCode(),
                commissionProperties.driverSharePercentage());

        boolean isFirstAccount = !payoutRecipientRepository.existsByDriverId(driverId);
        PayoutRecipient recipient = PayoutRecipient.builder()
                .driverId(driverId)
                .bankName(request.bankName())
                .bankCode(request.bankCode())
                .accountNumber(request.accountNumber())
                .accountName(resolved.accountName())
                .gatewaySubaccountCode(subaccount.code())
                .gatewaySubaccountId(subaccount.id())
                .isDefault(isFirstAccount)
                .build();

        return payoutRecipientRepository.save(recipient);
    }

    /** "Select preferred payout account" - switches which saved account future fares are split to. */
    @Transactional
    public PayoutRecipient selectDefault(String driverId, String callerId, String recipientId) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can choose their own payout account");
        }
        payoutRecipientRepository.clearDefaultForDriver(driverId);
        int updated = payoutRecipientRepository.setDefault(recipientId, driverId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Payout recipient not found");
        }

        // First load of this row in this transaction - guaranteed to see the update just made, not a stale cached instance.
        return payoutRecipientRepository.findByIdAndDriverId(recipientId, driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout recipient not found"));
    }
}

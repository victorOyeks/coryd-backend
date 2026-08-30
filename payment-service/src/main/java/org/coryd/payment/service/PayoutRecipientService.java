package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.dto.PayoutRecipientRequest;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PayoutRecipientService {

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final PaystackGatewayClient paystackGatewayClient;

    public List<PaystackGatewayClient.BankOption> listBanks() {
        return paystackGatewayClient.listBanks();
    }

    /** "Confirm this is you" step before saving - resolves against the bank, doesn't touch anything yet. */
    public PaystackGatewayClient.ResolvedAccount resolve(String accountNumber, String bankCode) {
        return paystackGatewayClient.resolveAccountNumber(accountNumber, bankCode);
    }

    /** Payout Setup screen's saved-accounts list. */
    public List<PayoutRecipient> list(String driverId) {
        return payoutRecipientRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
    }

    /** Adds a new saved bank account - re-resolves server-side (never trusts a client-supplied
     * account name) and registers the real Paystack transfer recipient, gatewayRecipientCode
     * being non-null is what "Verified" means on this screen. The first account a driver ever
     * saves becomes their default automatically; later ones are added as non-default until
     * explicitly selected. */
    @Transactional
    public PayoutRecipient add(String driverId, String callerId, PayoutRecipientRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can add their own payout bank account");
        }
        PaystackGatewayClient.ResolvedAccount resolved = paystackGatewayClient.resolveAccountNumber(request.accountNumber(), request.bankCode());
        String recipientCode = paystackGatewayClient.createTransferRecipient(resolved.accountName(), request.accountNumber(), request.bankCode());

        boolean isFirstAccount = !payoutRecipientRepository.existsByDriverId(driverId);
        PayoutRecipient recipient = PayoutRecipient.builder()
                .driverId(driverId)
                .bankName(request.bankName())
                .bankCode(request.bankCode())
                .accountNumber(request.accountNumber())
                .accountName(resolved.accountName())
                .gatewayRecipientCode(recipientCode)
                .isDefault(isFirstAccount)
                .build();

        return payoutRecipientRepository.save(recipient);
    }

    /** "Select preferred payout account" - switches which saved, already-verified account future withdrawals go to. */
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

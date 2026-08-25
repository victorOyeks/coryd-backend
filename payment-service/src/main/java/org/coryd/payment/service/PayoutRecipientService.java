package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
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

    /** Re-resolves server-side (never trusts a client-supplied account name) and registers the real Paystack transfer recipient - gatewayRecipientCode being non-null is what "Verified" means on the Withdraw Funds screen. */
    @Transactional
    public PayoutRecipient upsert(String driverId, String callerId, PayoutRecipientRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can set their own payout bank account");
        }
        PaystackGatewayClient.ResolvedAccount resolved = paystackGatewayClient.resolveAccountNumber(request.accountNumber(), request.bankCode());
        String recipientCode = paystackGatewayClient.createTransferRecipient(resolved.accountName(), request.accountNumber(), request.bankCode());

        PayoutRecipient recipient = payoutRecipientRepository.findByDriverId(driverId)
                .orElseGet(() -> PayoutRecipient.builder().driverId(driverId).build());
        recipient.setBankName(request.bankName());
        recipient.setBankCode(request.bankCode());
        recipient.setAccountNumber(request.accountNumber());
        recipient.setAccountName(resolved.accountName());
        recipient.setGatewayRecipientCode(recipientCode);

        return payoutRecipientRepository.save(recipient);
    }
}

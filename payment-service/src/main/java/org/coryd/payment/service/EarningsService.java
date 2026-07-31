package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.config.KafkaConfig;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final CommissionProperties commissionProperties;
    private final WalletService walletService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onTripCompleted(TripCompletedEvent event) {
        String bookingId = event.getBookingId();
        FareHold hold = fareHoldRepository.findByBookingId(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            log.warn("No releasable fare hold for booking {}", bookingId);
            return;
        }

        BigDecimal commission = hold.getAmount().multiply(commissionProperties.getPercentage());
        BigDecimal net = hold.getAmount().subtract(commission);

        hold.setReleased(true);
        fareHoldRepository.save(hold);

        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(hold.getDriverId())
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(hold.getDriverId()).build());
        account.setAvailableBalance(account.getAvailableBalance().add(net));
        account.setLifetimeGross(account.getLifetimeGross().add(hold.getAmount()));
        account.setLifetimeCommission(account.getLifetimeCommission().add(commission));
        earningsAccountRepository.save(account);

        earningsTransactionRepository.save(EarningsTransaction.builder()
                .driverId(hold.getDriverId())
                .bookingId(bookingId)
                .grossFare(hold.getAmount())
                .commissionAmount(commission)
                .netCredit(net)
                .build());
    }

    @Transactional
    public void refund(String bookingId, String reason) {
        FareHold hold = fareHoldRepository.findByBookingId(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            return;
        }
        hold.setRefunded(true);
        fareHoldRepository.save(hold);

        paymentTransactionRepository.findByBookingId(bookingId).ifPresent(transaction -> {
            // We fully own the wallet ledger, so a wallet-paid booking gets its money back
            // immediately. A Paystack-paid booking still only emits RefundIssuedEvent below -
            // there is no actual Paystack refund API call yet (pre-existing gap, unchanged here).
            if (transaction.getPaymentMethod() == PaymentMethod.WALLET) {
                walletService.creditRefund(transaction.getRiderId(), bookingId, hold.getAmount(), reason);
            }

            kafkaTemplate.send(KafkaTopics.REFUND_ISSUED, RefundIssuedEvent.builder()
                    .bookingId(bookingId.toString())
                    .riderId(transaction.getRiderId().toString())
                    .amount(hold.getAmount())
                    .reason(reason)
                    .refundedAt(LocalDateTime.now())
                    .build());
        });
    }
}

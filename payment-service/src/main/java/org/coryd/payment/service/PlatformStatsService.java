package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.payment.dto.PaymentStatsResponse;
import org.coryd.payment.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final PlatformStatsRepository platformStatsRepository;

    @Transactional(readOnly = true)
    public PaymentStatsResponse statsFor(Instant from, Instant to) {
        PlatformStatsRepository.EarningsSums earnings = platformStatsRepository.earnings(from, to);
        PlatformStatsRepository.MethodSplit split = platformStatsRepository.methodSplit(from, to);
        PlatformStatsRepository.RefundSums refunds = platformStatsRepository.refunds(from, to);
        PlatformStatsRepository.PayoutSums payouts = platformStatsRepository.payouts(from, to);

        return new PaymentStatsResponse(
                zeroIfNull(earnings.getGrossFare()),
                zeroIfNull(split.getCardAmount()),
                zeroIfNull(split.getWalletAmount()),
                zeroIfNull(earnings.getCommission()),
                zeroIfNull(earnings.getNetCredit()),
                refunds.getCount(),
                zeroIfNull(refunds.getAmount()),
                payouts.getCount(),
                zeroIfNull(payouts.getAmount()),
                zeroIfNull(platformStatsRepository.unsettledDriverBalance()));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.payment.dto.PaymentStatsResponse;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final PlatformStatsRepository platformStatsRepository;
    private final DriverSettlementRepository driverSettlementRepository;

    @Transactional(readOnly = true)
    public PaymentStatsResponse statsFor(Instant from, Instant to) {
        PlatformStatsRepository.EarningsSums earnings = platformStatsRepository.earnings(from, to);
        PlatformStatsRepository.RefundSums refunds = platformStatsRepository.refunds(from, to);
        DriverSettlementRepository.Totals payouts = driverSettlementRepository.settledBetween(from, to);

        return new PaymentStatsResponse(
                zeroIfNull(earnings.getGrossFare()),
                zeroIfNull(earnings.getCommission()),
                zeroIfNull(earnings.getNetCredit()),
                refunds.getCount(),
                zeroIfNull(refunds.getAmount()),
                payouts.getCount(),
                zeroIfNull(payouts.getAmount()),
                zeroIfNull(platformStatsRepository.driverDebt()));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

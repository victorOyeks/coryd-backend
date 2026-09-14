package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.identity.dto.IdentityStatsResponse;
import org.coryd.identity.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final PlatformStatsRepository platformStatsRepository;

    @Transactional(readOnly = true)
    public IdentityStatsResponse statsFor(Instant from, Instant to) {
        PlatformStatsRepository.SignupCounts counts = platformStatsRepository.signupCounts(from, to);
        return new IdentityStatsResponse(
                counts.getNewRiders(),
                counts.getNewDrivers(),
                counts.getTotalRiders(),
                counts.getTotalDrivers());
    }
}

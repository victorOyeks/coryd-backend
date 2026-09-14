package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.profile.dto.ProfileStatsResponse;
import org.coryd.profile.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final PlatformStatsRepository platformStatsRepository;

    @Transactional(readOnly = true)
    public ProfileStatsResponse stats() {
        PlatformStatsRepository.SupplyCounts counts = platformStatsRepository.supplyCounts();
        return new ProfileStatsResponse(
                counts.getApprovedDrivers(),
                counts.getAwaitingApproval(),
                counts.getOldestPendingAt());
    }
}

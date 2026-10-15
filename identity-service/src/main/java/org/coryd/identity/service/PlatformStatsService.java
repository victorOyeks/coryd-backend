package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.identity.dto.HeardAboutUsStatsResponse;
import org.coryd.identity.dto.IdentityStatsResponse;
import org.coryd.identity.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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

    @Transactional(readOnly = true)
    public HeardAboutUsStatsResponse heardAboutUs(Instant from, Instant to) {
        List<HeardAboutUsStatsResponse.Count> bySource = platformStatsRepository.heardAboutUs(from, to).stream()
                .map(c -> new HeardAboutUsStatsResponse.Count(c.getSource(), c.getSignups())).toList();
        List<HeardAboutUsStatsResponse.Count> other = platformStatsRepository.heardAboutUsOther(from, to).stream()
                .map(c -> new HeardAboutUsStatsResponse.Count(c.getSource(), c.getSignups())).toList();
        return new HeardAboutUsStatsResponse(from, to, bySource.stream().mapToLong(HeardAboutUsStatsResponse.Count::signups).sum(),
                bySource, other);
    }
}

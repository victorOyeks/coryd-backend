package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.identity.dto.DataSubjectCountResponse;
import org.coryd.identity.dto.HeardAboutUsStatsResponse;
import org.coryd.identity.dto.IdentityStatsResponse;
import org.coryd.identity.repository.BetaSignupRepository;
import org.coryd.identity.repository.PlatformStatsRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final PlatformStatsRepository platformStatsRepository;
    private final UserRepository userRepository;
    private final BetaSignupRepository betaSignupRepository;

    /** Everyone we hold personal data about - every account, plus waitlist sign-ups who haven't joined. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public DataSubjectCountResponse dataSubjects() {
        long users = userRepository.count();
        long waitlistOnly = betaSignupRepository.countByJoinedAtIsNull();
        return new DataSubjectCountResponse(users, waitlistOnly, users + waitlistOnly);
    }

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

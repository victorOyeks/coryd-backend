package org.coryd.trip.repository;

import org.coryd.trip.entity.IdentityVerificationCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityVerificationCacheRepository extends JpaRepository<IdentityVerificationCache, String> {

    boolean existsByUserIdAndVerifiedTrue(String userId);
}

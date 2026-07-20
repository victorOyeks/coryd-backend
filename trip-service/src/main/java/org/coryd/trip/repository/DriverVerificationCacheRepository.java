package org.coryd.trip.repository;

import org.coryd.trip.entity.DriverVerificationCache;
import org.springframework.data.jpa.repository.JpaRepository;


public interface DriverVerificationCacheRepository extends JpaRepository<DriverVerificationCache, String> {
}

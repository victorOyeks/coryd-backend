package org.coryd.trip.repository;

import org.coryd.trip.entity.SuggestedFareRange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SuggestedFareRangeRepository extends JpaRepository<SuggestedFareRange, String> {
    Optional<SuggestedFareRange> findByCorridorKey(String corridorKey);
}

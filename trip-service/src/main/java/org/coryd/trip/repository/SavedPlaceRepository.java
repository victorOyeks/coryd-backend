package org.coryd.trip.repository;

import org.coryd.trip.entity.SavedPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, String> {
    List<SavedPlace> findByUserIdOrderByCreatedAtAsc(String userId);

    java.util.Optional<SavedPlace> findFirstByUserIdAndType(String userId, org.coryd.common.enums.SavedPlaceType type);

    List<SavedPlace> findByUserIdInAndType(java.util.Collection<String> userIds, org.coryd.common.enums.SavedPlaceType type);
}

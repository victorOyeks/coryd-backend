package org.coryd.trip.repository;

import org.coryd.trip.entity.SavedPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, String> {
    List<SavedPlace> findByRiderIdOrderByCreatedAtAsc(String riderId);
}

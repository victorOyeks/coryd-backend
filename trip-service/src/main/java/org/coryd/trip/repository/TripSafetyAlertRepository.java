package org.coryd.trip.repository;

import org.coryd.trip.entity.TripSafetyAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TripSafetyAlertRepository extends JpaRepository<TripSafetyAlert, String> {

    boolean existsByRoutePostIdAndKindAndStatus(String routePostId, TripSafetyAlert.Kind kind, TripSafetyAlert.Status status);

    boolean existsByRoutePostIdAndKindAndCreatedAtAfter(String routePostId, TripSafetyAlert.Kind kind, java.time.Instant since);

    List<TripSafetyAlert> findByStatusAndCreatedAtBefore(TripSafetyAlert.Status status, Instant before);

    Page<TripSafetyAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

package org.coryd.trip.repository;

import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByRoutePostId(String routePostId);
    List<Booking> findTop5ByRiderIdOrderByCreatedAtDesc(String riderId);
    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant before);
}

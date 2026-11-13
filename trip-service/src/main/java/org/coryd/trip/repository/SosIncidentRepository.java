package org.coryd.trip.repository;

import org.coryd.trip.entity.SosIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface SosIncidentRepository extends JpaRepository<SosIncident, String> {

    /** Ops Console SOS list, newest first. */
    Page<SosIncident> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** One trip's SOS - raised on the route by its driver, or on one of its bookings by a rider. */
    List<SosIncident> findByRoutePostIdOrBookingIdIn(String routePostId, java.util.Collection<String> bookingIds);

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
}

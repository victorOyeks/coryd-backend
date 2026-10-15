package org.coryd.trip.repository;

import org.coryd.trip.entity.SosIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface SosIncidentRepository extends JpaRepository<SosIncident, String> {

    /** Ops Console SOS list, newest first. */
    Page<SosIncident> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
}

package org.coryd.trip.repository;

import org.coryd.trip.entity.SosIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface SosIncidentRepository extends JpaRepository<SosIncident, String> {

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
}

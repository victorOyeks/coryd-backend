package org.coryd.trip.repository;

import org.coryd.trip.entity.SosRealert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SosRealertRepository extends JpaRepository<SosRealert, String> {

    List<SosRealert> findByIncidentIdOrderByCreatedAtAsc(String incidentId);
}

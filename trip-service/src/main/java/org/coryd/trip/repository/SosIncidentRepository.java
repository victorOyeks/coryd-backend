package org.coryd.trip.repository;

import org.coryd.trip.entity.SosIncident;
import org.springframework.data.jpa.repository.JpaRepository;


public interface SosIncidentRepository extends JpaRepository<SosIncident, String> {
}

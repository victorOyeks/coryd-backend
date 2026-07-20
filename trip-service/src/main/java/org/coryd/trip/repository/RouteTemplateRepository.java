package org.coryd.trip.repository;

import org.coryd.trip.entity.RouteTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RouteTemplateRepository extends JpaRepository<RouteTemplate, String> {
    List<RouteTemplate> findByDriverId(String driverId);
}

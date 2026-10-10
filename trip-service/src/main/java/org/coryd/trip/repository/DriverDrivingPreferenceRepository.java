package org.coryd.trip.repository;

import org.coryd.trip.entity.DriverDrivingPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DriverDrivingPreferenceRepository extends JpaRepository<DriverDrivingPreference, String> {
    Optional<DriverDrivingPreference> findByDriverId(String driverId);
}

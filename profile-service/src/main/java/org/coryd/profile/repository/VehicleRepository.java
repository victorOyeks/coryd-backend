package org.coryd.profile.repository;

import org.coryd.profile.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    List<Vehicle> findByDriverId(String driverId);
    boolean existsByPlateNumber(String plateNumber);
}

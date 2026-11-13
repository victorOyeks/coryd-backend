package org.coryd.profile.repository;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    List<Vehicle> findByDriverId(String driverId);

    List<Vehicle> findByDriverIdIn(List<String> driverIds);
    boolean existsByPlateNumber(String plateNumber);

    /** Ops edits: a plate is the same plate whatever its case. */
    boolean existsByPlateNumberIgnoreCase(String plateNumber);

    /** A vehicle row only exists once a driver actually registers one, so status alone means
     * "submitted and awaiting a decision" - no separate "has it been submitted" check needed. */
    List<Vehicle> findByVerificationStatus(VerificationStatus status);
}

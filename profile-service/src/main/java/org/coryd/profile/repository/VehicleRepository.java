package org.coryd.profile.repository;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    List<Vehicle> findByDriverId(String driverId);

    List<Vehicle> findByDriverIdIn(List<String> driverIds);
    boolean existsByPlateNumber(String plateNumber);

    /** A plate is the same plate whatever its case, spaces or dashes - key is upper case letters and digits only. */
    @Query("""
            select count(v) > 0 from Vehicle v
            where replace(replace(upper(v.plateNumber), '-', ''), ' ', '') = :key and v.id <> :exceptVehicleId
            """)
    boolean existsByPlateKey(@Param("key") String key, @Param("exceptVehicleId") String exceptVehicleId);

    /** Ops edits: a plate is the same plate whatever its case. */
    boolean existsByPlateNumberIgnoreCase(String plateNumber);

    /** A vehicle row only exists once a driver actually registers one, so status alone means
     * "submitted and awaiting a decision" - no separate "has it been submitted" check needed. */
    List<Vehicle> findByVerificationStatus(VerificationStatus status);
}

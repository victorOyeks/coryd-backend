package org.coryd.profile.repository;

import org.coryd.profile.entity.FavoriteDriver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteDriverRepository extends JpaRepository<FavoriteDriver, String> {
    List<FavoriteDriver> findByRiderIdOrderByCreatedAtDesc(String riderId);

    Optional<FavoriteDriver> findByRiderIdAndDriverId(String riderId, String driverId);

    /** Who has favourited this driver - powers both the driver's own picker and the internal
     * cross-service validation call trip-service makes when a driver reserves seats. */
    List<FavoriteDriver> findByDriverId(String driverId);
}

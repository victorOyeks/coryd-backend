package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.FavoriteDriver;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.FavoriteDriverRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteDriverService {

    private final FavoriteDriverRepository favoriteDriverRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;

    /** The rider's own Favourites list - enriched with name/photo (mirrors listRidersWhoFavourited
     * below) so rendering it doesn't mean one profile lookup per favourite. Order is preserved from
     * findByRiderIdOrderByCreatedAtDesc (most recently favourited first) - the batch IN lookup below
     * doesn't guarantee that order on its own. */
    public List<DriverSummary> list(String riderId) {
        List<String> driverIds = favoriteDriverRepository.findByRiderIdOrderByCreatedAtDesc(riderId).stream()
                .map(FavoriteDriver::getDriverId)
                .toList();
        if (driverIds.isEmpty()) {
            return List.of();
        }
        Map<String, DriverProfile> profiles = driverProfileRepository.findByUserIdIn(driverIds).stream()
                .collect(Collectors.toMap(DriverProfile::getUserId, profile -> profile));
        return driverIds.stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .map(DriverSummary::from)
                .toList();
    }

    /** Idempotent - favouriting a driver twice is a no-op, not an error the client has to guard against. */
    @Transactional
    public FavoriteDriver add(String riderId, String driverId) {
        return favoriteDriverRepository.findByRiderIdAndDriverId(riderId, driverId)
                .orElseGet(() -> favoriteDriverRepository.save(FavoriteDriver.builder()
                        .riderId(riderId)
                        .driverId(driverId)
                        .build()));
    }

    @Transactional
    public void remove(String riderId, String driverId) {
        favoriteDriverRepository.findByRiderIdAndDriverId(riderId, driverId)
                .ifPresent(favoriteDriverRepository::delete);
    }

    /** The driver's own "who can I reserve a seat for" picker, and what trip-service's internal
     * favourited-by call resolves to for the route-creation validation - same underlying list,
     * enriched with name/photo here since the driver's UI needs something to show. */
    public List<RiderSummary> listRidersWhoFavourited(String driverId) {
        List<String> riderIds = favoriteDriverRepository.findByDriverId(driverId).stream()
                .map(FavoriteDriver::getRiderId)
                .toList();
        if (riderIds.isEmpty()) {
            return List.of();
        }
        return riderProfileRepository.findByUserIdIn(riderIds).stream()
                .map(RiderSummary::from)
                .toList();
    }
}

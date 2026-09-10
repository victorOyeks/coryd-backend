package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.entity.FavoriteDriver;
import org.coryd.profile.repository.FavoriteDriverRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FavoriteDriverService {

    private final FavoriteDriverRepository favoriteDriverRepository;
    private final RiderProfileRepository riderProfileRepository;

    public List<FavoriteDriver> list(String riderId) {
        return favoriteDriverRepository.findByRiderIdOrderByCreatedAtDesc(riderId);
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

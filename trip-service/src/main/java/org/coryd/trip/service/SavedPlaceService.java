package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.SavedPlaceType;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.dto.SaveHomeRequest;
import org.coryd.trip.dto.SavePlaceRequest;
import org.coryd.trip.entity.SavedPlace;
import org.coryd.trip.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SavedPlaceService {

    private final SavedPlaceRepository savedPlaceRepository;

    public List<SavedPlace> getPlaces(String userId) {
        return savedPlaceRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    /** A HOME sent here replaces the user's existing Home - there is only ever one. */
    @Transactional
    public SavedPlace addPlace(String userId, SavePlaceRequest request) {
        if (request.type() == SavedPlaceType.HOME) {
            return saveHome(userId, new SaveHomeRequest(request.label(), request.formattedAddress(), request.lat(), request.lng()));
        }
        return savedPlaceRepository.save(SavedPlace.builder()
                .userId(userId)
                .label(request.label())
                .formattedAddress(request.formattedAddress())
                .lat(request.lat())
                .lng(request.lng())
                .type(request.type() == null ? SavedPlaceType.GENERAL : request.type())
                .build());
    }

    public Optional<SavedPlace> getHome(String userId) {
        return savedPlaceRepository.findFirstByUserIdAndType(userId, SavedPlaceType.HOME);
    }

    /** Sets Home, replacing the one already there. */
    @Transactional
    public SavedPlace saveHome(String userId, SaveHomeRequest request) {
        SavedPlace home = savedPlaceRepository.findFirstByUserIdAndType(userId, SavedPlaceType.HOME)
                .orElseGet(() -> SavedPlace.builder().userId(userId).type(SavedPlaceType.HOME).build());
        home.setLabel(request.label().trim());
        home.setFormattedAddress(request.formattedAddress() == null || request.formattedAddress().isBlank()
                ? request.label().trim() : request.formattedAddress().trim());
        home.setLat(request.lat());
        home.setLng(request.lng());
        return savedPlaceRepository.save(home);
    }

    @Transactional
    public void deletePlace(String userId, String placeId) {
        SavedPlace place = savedPlaceRepository.findById(placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Saved place not found"));
        if (!userId.equals(place.getUserId())) {
            throw new UnauthorizedException("Not authorized for this saved place");
        }
        savedPlaceRepository.delete(place);
    }
}

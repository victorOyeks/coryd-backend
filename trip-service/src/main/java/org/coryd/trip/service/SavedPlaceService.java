package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.SavedPlaceType;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.dto.SavePlaceRequest;
import org.coryd.trip.entity.SavedPlace;
import org.coryd.trip.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SavedPlaceService {

    private final SavedPlaceRepository savedPlaceRepository;

    public List<SavedPlace> getPlaces(String riderId) {
        return savedPlaceRepository.findByRiderIdOrderByCreatedAtAsc(riderId);
    }

    @Transactional
    public SavedPlace addPlace(String riderId, SavePlaceRequest request) {
        return savedPlaceRepository.save(SavedPlace.builder()
                .riderId(riderId)
                .label(request.label())
                .formattedAddress(request.formattedAddress())
                .lat(request.lat())
                .lng(request.lng())
                .type(request.type() == null ? SavedPlaceType.GENERAL : request.type())
                .build());
    }

    @Transactional
    public void deletePlace(String riderId, String placeId) {
        SavedPlace place = savedPlaceRepository.findById(placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Saved place not found"));
        if (!riderId.equals(place.getRiderId())) {
            throw new UnauthorizedException("Not authorized for this saved place");
        }
        savedPlaceRepository.delete(place);
    }
}

package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.config.FareProperties;
import org.coryd.trip.dto.FareSuggestionRequest;
import org.coryd.trip.dto.FareSuggestionResponse;
import org.coryd.trip.util.PolylineDecoder;
import org.coryd.trip.util.PolylineMath;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** The price a driver sees while posting a route, worked out from the length of their whole road. */
@Service
@RequiredArgsConstructor
public class FareSuggestionService {

    private final MapsService mapsService;
    private final FareProperties fareProperties;

    public FareSuggestionResponse suggest(FareSuggestionRequest request) {
        double distanceKm = Math.round(roadKm(request) * 10.0) / 10.0;
        return new FareSuggestionResponse(distanceKm, fareProperties.getPerKm(), fareFor(distanceKm));
    }

    /** The picked road's own length when the driver chose one; otherwise Google's usual road (cached for a week). */
    private double roadKm(FareSuggestionRequest request) {
        if (request.routePolyline() != null && !request.routePolyline().isBlank()) {
            List<PolylineDecoder.LatLng> road = PolylineDecoder.decode(request.routePolyline());
            if (road.size() >= 2) {
                return PolylineMath.lengthMeters(road) / 1000.0;
            }
        }
        return mapsService.getRoutePreview(request.originLat(), request.originLng(),
                request.destinationLat(), request.destinationLng()).distanceKm();
    }

    BigDecimal fareFor(double distanceKm) {
        BigDecimal step = fareProperties.getRoundTo();
        BigDecimal fare = BigDecimal.valueOf(distanceKm).multiply(fareProperties.getPerKm())
                .divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        return fare.max(fareProperties.getMinimum());
    }
}

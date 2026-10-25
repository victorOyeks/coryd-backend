package org.coryd.trip.service;

import org.coryd.trip.config.FareProperties;
import org.coryd.trip.dto.FareSuggestionRequest;
import org.coryd.trip.dto.RoutePreview;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FareSuggestionServiceTest {

    private final MapsService mapsService = mock(MapsService.class);
    private final FareSuggestionService service = new FareSuggestionService(mapsService, new FareProperties());

    @Test
    void ikoroduToVictoriaIslandIsFourThousand() {
        when(mapsService.getRoutePreview(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new RoutePreview(39.6, 55, "55 mins", "Ikorodu Rd", null));

        var suggestion = service.suggest(new FareSuggestionRequest(6.62, 3.51, 6.43, 3.42, null));

        assertThat(suggestion.distanceKm()).isEqualTo(39.6);
        assertThat(suggestion.ratePerKm()).isEqualByComparingTo("100");
        assertThat(suggestion.suggestedFarePerSeat()).isEqualByComparingTo("4000");
    }

    @Test
    void roundsToTheNearestHundredAndNeverGoesBelowTheMinimum() {
        assertThat(service.fareFor(32.9)).isEqualByComparingTo("3300");
        assertThat(service.fareFor(32.4)).isEqualByComparingTo("3200");
        assertThat(service.fareFor(2.1)).isEqualByComparingTo("500");
    }

    @Test
    void aPickedRoadIsPricedByItsOwnLength() {
        // Straight north along a meridian: 0.1 degrees of latitude is about 11.1 km.
        String road = "_ibE_ibE_pR?"; // (1.0, 1.0) -> (1.1, 1.0)

        var suggestion = service.suggest(new FareSuggestionRequest(1.0, 1.0, 1.1, 1.0, road));

        assertThat(suggestion.distanceKm()).isEqualTo(11.1);
        assertThat(suggestion.suggestedFarePerSeat()).isEqualByComparingTo(new BigDecimal("1100"));
        verify(mapsService, never()).getRoutePreview(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}

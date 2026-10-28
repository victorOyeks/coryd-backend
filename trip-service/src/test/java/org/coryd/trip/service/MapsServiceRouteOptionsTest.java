package org.coryd.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.trip.client.GoogleMapsClient;
import org.coryd.trip.dto.RoutePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapsServiceRouteOptionsTest {

    private final GoogleMapsClient googleMapsClient = mock(GoogleMapsClient.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> redis = new HashMap<>();
    private final MapsService mapsService = new MapsService(googleMapsClient, redisTemplate, new ObjectMapper());

    private static final RoutePreview MAIN = new RoutePreview(41.68, 58, "58 mins", "Ikorodu Rd and Expressway1/E1", "abc|def");
    private static final RoutePreview OSBORNE = new RoutePreview(41.99, 59, "59 mins", "Ikorodu Rd and Osborne Rd", "ghi");
    private static final RoutePreview A1 = new RoutePreview(40.8, 61, "1 hour 1 min", "A1", "jkl");

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> redis.get(inv.<String>getArgument(0)));
        doAnswer(inv -> redis.put(inv.getArgument(0), inv.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void severalRoadsAreKeptSoTheScreenShowsTheSameOnesNextTime() {
        when(googleMapsClient.getRouteOptions(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(MAIN, OSBORNE, A1))
                .thenReturn(List.of(MAIN));

        List<RoutePreview> first = mapsService.getRouteOptions(6.6206269, 3.503486, 6.4308465, 3.4306265, List.of());
        List<RoutePreview> second = mapsService.getRouteOptions(6.6206269, 3.503486, 6.4308465, 3.4306265, List.of());

        assertThat(first).containsExactly(MAIN, OSBORNE, A1);
        assertThat(second).containsExactly(MAIN, OSBORNE, A1);
        verify(googleMapsClient, times(1)).getRouteOptions(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void aLoneRoadIsNotKeptSoTheNextOpenAsksGoogleAgain() {
        when(googleMapsClient.getRouteOptions(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(MAIN))
                .thenReturn(List.of(MAIN, OSBORNE, A1));

        assertThat(mapsService.getRouteOptions(6.62, 3.50, 6.43, 3.43, List.of())).containsExactly(MAIN);
        assertThat(mapsService.getRouteOptions(6.62, 3.50, 6.43, 3.43, List.of())).containsExactly(MAIN, OSBORNE, A1);
        verify(googleMapsClient, times(2)).getRouteOptions(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void roadsThroughViaPointsAreNotCached() {
        List<double[]> via = List.of(new double[]{6.55, 3.38});
        when(googleMapsClient.getRouteOptions(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(MAIN, OSBORNE));

        mapsService.getRouteOptions(6.62, 3.50, 6.43, 3.43, via);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}

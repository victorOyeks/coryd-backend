package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.PilotMetricsResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteSearch;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PilotMetricsServiceTest {

    private final RouteSearchRepository searches = mock(RouteSearchRepository.class);
    private final RoutePostRepository routes = mock(RoutePostRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final NotifyMeRequestRepository notifyMe = mock(NotifyMeRequestRepository.class);
    private final PilotMetricsService service = new PilotMetricsService(searches, routes, bookings, notifyMe);

    @Test
    void searchSuccessBookingAndFillAreWorkedOut() {
        when(searches.findByCreatedAtAfter(any())).thenReturn(List.of(
                search("rider-1", 1), search("rider-1", 0), search("rider-2", 2), search("rider-2", 0)));
        RoutePost route = RoutePost.builder().id("route-1").seatCapacity(4).status(RouteStatus.PUBLISHED)
                .originLat(6.62).originLng(3.51).destinationLat(6.43).destinationLng(3.42).build();
        when(routes.findByStatusInAndDepartureTimeBetween(argThat((Collection<RouteStatus> s) -> s.contains(RouteStatus.PUBLISHED)), any(), any()))
                .thenReturn(List.of(route));
        when(bookings.findByRoutePostIdIn(anyList())).thenReturn(List.of(
                Booking.builder().routePostId("route-1").riderId("rider-1").seatCount(2).status(BookingStatus.CONFIRMED).build(),
                Booking.builder().routePostId("route-1").riderId("rider-3").seatCount(1).status(BookingStatus.CANCELLED_RIDER).build()));
        when(notifyMe.countByCreatedAtAfter(any())).thenReturn(5L);
        when(notifyMe.countBookedSince(any())).thenReturn(1L);

        PilotMetricsResponse metrics = service.metrics(7);

        assertThat(metrics.totals().searchSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.totals().searchToBookingRate()).isEqualTo(0.5);
        assertThat(metrics.totals().fillRate()).as("a cancelled seat doesn't count").isEqualTo(0.5);
        assertThat(metrics.notifyMeBooked()).isEqualTo(1);
        assertThat(metrics.topSearchedRoutes()).singleElement().satisfies(r -> {
            assertThat(r.riders()).isEqualTo(2);
            assertThat(r.searchesWithNoResults()).isEqualTo(2);
        });
    }

    private static RouteSearch search(String riderId, int results) {
        return RouteSearch.builder().riderId(riderId).resultCount(results).originLabel("Ikorodu").destinationLabel("VI")
                .originLat(6.62).originLng(3.51).destinationLat(6.43).destinationLng(3.42).build();
    }
}

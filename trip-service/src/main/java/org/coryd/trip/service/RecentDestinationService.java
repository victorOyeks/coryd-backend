package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.RecentDestination;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where this user went lately, newest first - drop-offs of seats they booked and the ends of trips they drove,
 * for the one-tap "leaving now" picker. Places within a short walk of each other are one place.
 */
@Service
@RequiredArgsConstructor
public class RecentDestinationService {

    /** Closer than this, two destinations are the same place. */
    static final double SAME_PLACE_KM = 0.3;
    static final int MAX_LIMIT = 20;
    private static final List<BookingStatus> RODE = List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED);

    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;

    @Transactional(readOnly = true)
    public List<RecentDestination> recent(String userId, int limit) {
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<RecentDestination> all = new ArrayList<>();
        List<Booking> seats = bookingRepository.findTop50ByRiderIdAndStatusInOrderByCreatedAtDesc(userId, RODE);
        Map<String, RoutePost> routes = new HashMap<>();
        routePostRepository.findAllById(seats.stream().map(Booking::getRoutePostId).distinct().toList())
                .forEach(r -> routes.put(r.getId(), r));
        for (Booking seat : seats) {
            RoutePost route = routes.get(seat.getRoutePostId());
            if (seat.getDropoffLat() != null && seat.getDropoffLng() != null) {
                all.add(new RecentDestination(label(seat.getDropoffLabel(), route), seat.getDropoffLat(), seat.getDropoffLng(), seat.getCreatedAt()));
            } else if (route != null) {
                all.add(new RecentDestination(route.getDestinationLabel(), route.getDestinationLat(), route.getDestinationLng(), seat.getCreatedAt()));
            }
        }
        for (RoutePost drove : routePostRepository.findTop50ByDriverIdAndStatusNotOrderByCreatedAtDesc(userId, RouteStatus.CANCELLED)) {
            all.add(new RecentDestination(drove.getDestinationLabel(), drove.getDestinationLat(), drove.getDestinationLng(), drove.getCreatedAt()));
        }
        all.sort(Comparator.comparing(RecentDestination::lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<RecentDestination> distinct = new ArrayList<>();
        for (RecentDestination place : all) {
            boolean seen = distinct.stream().anyMatch(d -> GeoMath.distanceKm(d.lat(), d.lng(), place.lat(), place.lng()) < SAME_PLACE_KM);
            if (!seen) {
                distinct.add(place);
                if (distinct.size() == max) {
                    break;
                }
            }
        }
        return distinct;
    }

    private static String label(String dropoffLabel, RoutePost route) {
        return dropoffLabel != null && !dropoffLabel.isBlank() ? dropoffLabel : route == null ? null : route.getDestinationLabel();
    }
}

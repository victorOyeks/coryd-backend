package org.coryd.trip.repository;

import org.coryd.trip.entity.NotifyMeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotifyMeRequestRepository extends JpaRepository<NotifyMeRequest, String> {

    List<NotifyMeRequest> findByFulfilledFalseAndExpiresAtAfter(Instant now);

    /** Still-wanted requests - what rider demand counts alongside searches. */
    List<NotifyMeRequest> findByCancelledFalseAndFulfilledFalseAndExpiresAtAfter(Instant now);

    /** A rider's own requests that can still match, newest first. */
    List<NotifyMeRequest> findByRiderIdAndCancelledFalseAndFulfilledFalseAndExpiresAtAfterOrderByCreatedAtDesc(String riderId, Instant now);

    long countByRiderIdAndCancelledFalseAndFulfilledFalseAndExpiresAtAfter(String riderId, Instant now);

    /** Repeating requests on this weekday with both ends in range - the time of day is checked by the caller. */
    @Query(value = """
            select * from trip.notify_me_requests n
            where n.cancelled = false
              and n.days_mask is not null
              and n.expires_at > :now
              and (n.days_mask & :dayBit) <> 0
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:originLat)) * cos(radians(n.origin_lat)) * cos(radians(n.origin_lng) - radians(:originLng))
                    + sin(radians(:originLat)) * sin(radians(n.origin_lat))
                  )))) <= :radiusKm
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:destLat)) * cos(radians(n.destination_lat)) * cos(radians(n.destination_lng) - radians(:destLng))
                    + sin(radians(:destLat)) * sin(radians(n.destination_lat))
                  )))) <= :radiusKm
            """, nativeQuery = true)
    List<NotifyMeRequest> findMatchingRepeating(@Param("now") Instant now, @Param("dayBit") int dayBit,
                                                @Param("originLat") double originLat, @Param("originLng") double originLng,
                                                @Param("destLat") double destLat, @Param("destLng") double destLng,
                                                @Param("radiusKm") double radiusKm);

    /** Still-active requests already told about this ride and not yet told a seat opened on it. */
    @Query(value = """
            select n.* from trip.notify_me_requests n
            join trip.notify_me_notifications x on x.request_id = n.id
            where x.route_post_id = :routePostId
              and x.seat_opened_at is null
              and n.cancelled = false
              and n.fulfilled = false
              and n.expires_at > :now
            """, nativeQuery = true)
    List<NotifyMeRequest> findToldAboutRide(@Param("routePostId") String routePostId, @Param("now") Instant now);

    /** 1 the first time a seat-opened push is claimed for this request and ride, 0 after. */
    @Modifying
    @Query(value = "update trip.notify_me_notifications set seat_opened_at = now() "
            + "where request_id = :requestId and route_post_id = :routePostId and seat_opened_at is null", nativeQuery = true)
    int claimSeatOpened(@Param("requestId") String requestId, @Param("routePostId") String routePostId);

    /** Whether the rider already holds or has a seat on any ride this request told them about. */
    @Query(value = """
            select count(*) > 0 from trip.bookings b
            join trip.notify_me_notifications x on x.route_post_id = b.route_post_id
            where x.request_id = :requestId and b.rider_id = :riderId and b.status in (:statuses)
            """, nativeQuery = true)
    boolean hasSeatOnAnnouncedRide(@Param("requestId") String requestId, @Param("riderId") String riderId,
                                   @Param("statuses") Collection<String> statuses);

    /** 1 the first time this request is told about this ride, 0 after - so a ride is announced once. */
    @Modifying
    @Query(value = "insert into trip.notify_me_notifications (id, request_id, route_post_id, created_at) "
            + "values (:id, :requestId, :routePostId, now()) on conflict (request_id, route_post_id) do nothing", nativeQuery = true)
    int recordNotification(@Param("id") String id, @Param("requestId") String requestId, @Param("routePostId") String routePostId);

    /** Same Haversine radius match as RoutePostRepository.search - see the comment there. */
    @Query(value = """
            select * from trip.notify_me_requests n
            where n.fulfilled = false
              and n.cancelled = false
              and n.expires_at > :now
              and n.desired_departure_time between :earliest and :latest
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:originLat)) * cos(radians(n.origin_lat)) * cos(radians(n.origin_lng) - radians(:originLng))
                    + sin(radians(:originLat)) * sin(radians(n.origin_lat))
                  )))) <= :radiusKm
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:destLat)) * cos(radians(n.destination_lat)) * cos(radians(n.destination_lng) - radians(:destLng))
                    + sin(radians(:destLat)) * sin(radians(n.destination_lat))
                  )))) <= :radiusKm
            """, nativeQuery = true)
    List<NotifyMeRequest> findMatching(@Param("now") Instant now,
                                        @Param("earliest") Instant earliest,
                                        @Param("latest") Instant latest,
                                        @Param("originLat") double originLat,
                                        @Param("originLng") double originLng,
                                        @Param("destLat") double destLat,
                                        @Param("destLng") double destLng,
                                        @Param("radiusKm") double radiusKm);
}

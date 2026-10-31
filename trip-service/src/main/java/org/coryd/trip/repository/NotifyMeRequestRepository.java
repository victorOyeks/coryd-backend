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

    List<NotifyMeRequest> findByCreatedAtAfterAndDesiredDepartureTimeIsNotNull(Instant since);

    /** One-off requests still waiting, starting inside a box and wanting to leave between from and to - the caller checks the exact distance. */
    @Query("""
            select n from NotifyMeRequest n
            where n.cancelled = false and n.fulfilled = false and n.expiresAt > :now
              and n.desiredDepartureTime between :from and :to
              and n.originLat between :minLat and :maxLat and n.originLng between :minLng and :maxLng
            """)
    List<NotifyMeRequest> findWaitingOneOffsStartingIn(@Param("now") Instant now, @Param("from") Instant from, @Param("to") Instant to,
                                                       @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                                       @Param("minLng") double minLng, @Param("maxLng") double maxLng);

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

    long countByCreatedAtAfter(Instant since);

    /**
     * PostGIS: still-waiting requests whose start and end are both within walkMeters of this route's
     * road, start first. Only callable where GeoCapabilities.postgis() is true; NotifyMeService still
     * checks the time and the day.
     */
    @Query(value = """
            select n.* from trip.notify_me_requests n
            join trip.route_posts r on r.id = :routePostId
            where n.cancelled = false and n.fulfilled = false and n.expires_at > :now
              and ST_DWithin(r.route_geog, ST_SetSRID(ST_MakePoint(n.origin_lng, n.origin_lat), 4326)::geography, :walkMeters)
              and ST_DWithin(r.route_geog, ST_SetSRID(ST_MakePoint(n.destination_lng, n.destination_lat), 4326)::geography, :walkMeters)
              and ST_LineLocatePoint(r.route_geog::geometry, ST_SetSRID(ST_MakePoint(n.origin_lng, n.origin_lat), 4326))
                < ST_LineLocatePoint(r.route_geog::geometry, ST_SetSRID(ST_MakePoint(n.destination_lng, n.destination_lat), 4326))
            """, nativeQuery = true)
    List<NotifyMeRequest> findWaitingAlongRoute(@Param("routePostId") String routePostId, @Param("now") Instant now,
                                                @Param("walkMeters") double walkMeters);

    /** Requests made since `since` whose rider took a seat on a ride the request told them about. */
    @Query(value = """
            select count(distinct n.id) from trip.notify_me_requests n
            join trip.notify_me_notifications x on x.request_id = n.id
            join trip.bookings b on b.route_post_id = x.route_post_id and b.rider_id = n.rider_id
            where n.created_at > :since and b.confirmed_at is not null
            """, nativeQuery = true)
    long countBookedSince(@Param("since") Instant since);

    /** Whether any of this rider's requests was already told about this ride - two overlapping requests push once. */
    @Query(value = """
            select count(*) > 0 from trip.notify_me_notifications x
            join trip.notify_me_requests n on n.id = x.request_id
            where n.rider_id = :riderId and x.route_post_id = :routePostId
            """, nativeQuery = true)
    boolean riderToldAboutRide(@Param("riderId") String riderId, @Param("routePostId") String routePostId);

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

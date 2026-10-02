package org.coryd.trip.repository;

import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.entity.RoutePost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RoutePostRepository extends JpaRepository<RoutePost, String> {

    Page<RoutePost> findByDriverIdOrderByCreatedAtDesc(String driverId, Pageable pageable);

    /** A driver's routes that still occupy them - the overlap rule and the active/upcoming split. */
    List<RoutePost> findByDriverIdAndStatusIn(String driverId, Collection<RouteStatus> statuses);

    /** Dashboard "Trips this week" count - the routes to check bookings against. */
    List<RoutePost> findByDriverIdAndDepartureTimeBetween(String driverId, Instant start, Instant end);

    /** Driver Trip History screen's actual query - status is a bucket (Ongoing/Completed/Cancelled/All), search matches either end of the route. NULL check comes last, matching this repo's search convention. */
    @Query("""
            select r from RoutePost r
            where r.driverId = :driverId
              and r.status in :statuses
              and (lower(r.originLabel) like lower(concat('%', :search, '%'))
                or lower(r.destinationLabel) like lower(concat('%', :search, '%'))
                or cast(:search as string) is null)
            order by r.departureTime desc
            """)
    Page<RoutePost> searchDriverHistory(@Param("driverId") String driverId,
                                         @Param("statuses") List<RouteStatus> statuses,
                                         @Param("search") String search,
                                         Pageable pageable);

    /**
     * Real great-circle radius match (Haversine, km) on both origin and destination,
     * not a lat/lng box - a box's corners sit up to radius*sqrt(2) away, which
     * overshoots inconsistently by direction once the radius gets past a few hundred
     * meters. least/greatest clamp the acos() argument to [-1,1] since floating-point
     * rounding can otherwise push it a hair outside that domain for near-zero distances.
     */
    @Query(value = """
            select * from trip.route_posts r
            where r.status = 'PUBLISHED'
              and r.seats_available > 0
              and r.departure_time between :earliest and :latest
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:originLat)) * cos(radians(r.origin_lat)) * cos(radians(r.origin_lng) - radians(:originLng))
                    + sin(radians(:originLat)) * sin(radians(r.origin_lat))
                  )))) <= :radiusKm
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:destLat)) * cos(radians(r.destination_lat)) * cos(radians(r.destination_lng) - radians(:destLng))
                    + sin(radians(:destLat)) * sin(radians(r.destination_lat))
                  )))) <= :radiusKm
            order by r.departure_time asc
            """, nativeQuery = true)
    List<RoutePost> search(@Param("earliest") Instant earliest,
                            @Param("latest") Instant latest,
                            @Param("originLat") double originLat,
                            @Param("originLng") double originLng,
                            @Param("destLat") double destLat,
                            @Param("destLng") double destLng,
                            @Param("radiusKm") double radiusKm);

    /**
     * Powers the "Rides nearby" map - bookable routes (published, seats left, departing between now
     * and latest, not the caller's own) whose origin sits within radiusKm of the given point. Same
     * Haversine expression as search() (see its comment), matched on origin only since this is about
     * "where are rides starting near me."
     */
    @Query(value = """
            select * from trip.route_posts r
            where r.status = 'PUBLISHED'
              and r.seats_available > 0
              and r.departure_time > :now
              and r.departure_time <= :latest
              and r.driver_id <> :excludeDriverId
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:lat)) * cos(radians(r.origin_lat)) * cos(radians(r.origin_lng) - radians(:lng))
                    + sin(radians(:lat)) * sin(radians(r.origin_lat))
                  )))) <= :radiusKm
            order by r.departure_time asc
            """, nativeQuery = true)
    List<RoutePost> findNearbyOrigins(@Param("lat") double lat,
                                       @Param("lng") double lng,
                                       @Param("radiusKm") double radiusKm,
                                       @Param("now") Instant now,
                                       @Param("latest") Instant latest,
                                       @Param("excludeDriverId") String excludeDriverId);

    /**
     * Atomic seat hold - the conditional WHERE clause is what resolves the
     * last-seat race condition (Section 10.3/12) with a single SQL statement.
     */
    @Modifying
    @Query("""
            update RoutePost r
            set r.seatsAvailable = r.seatsAvailable - :seats
            where r.id = :id and r.seatsAvailable >= :seats
            """)
    int holdSeats(@Param("id") String id, @Param("seats") int seats);

    /** Capped at seatCapacity so a double release can never mint seats that were never there. */
    @Modifying
    @Query("""
            update RoutePost r
            set r.seatsAvailable = least(r.seatsAvailable + :seats, r.seatCapacity)
            where r.id = :id
            """)
    int releaseSeats(@Param("id") String id, @Param("seats") int seats);

    @Modifying
    @Query("update RoutePost r set r.status = 'FULL' where r.id = :id and r.seatsAvailable = 0")
    int markFullIfExhausted(@Param("id") String id);

    /** The other half of markFullIfExhausted - nothing previously reverted FULL back to PUBLISHED
     * once a cancellation freed a seat back up, so a route that was ever FULL stayed permanently
     * excluded from search() (which only ever matches status = 'PUBLISHED') even after it had
     * bookable seats again. The status='FULL' guard means this only ever touches a route actually
     * in that state - never resurrects a CANCELLED/COMPLETED/IN_PROGRESS route. */
    @Modifying
    @Query("update RoutePost r set r.status = 'PUBLISHED' where r.id = :id and r.status = 'FULL' and r.seatsAvailable > 0")
    int reopenIfSeatsAvailable(@Param("id") String id);

    @Modifying
    @Query("update RoutePost r set r.firstBookingLockedAt = :now where r.id = :id and r.firstBookingLockedAt is null")
    int lockOnFirstBooking(@Param("id") String id, @Param("now") Instant now);

    List<RoutePost> findByStatusAndDepartureTimeBefore(RouteStatus status, Instant before);

    /** RouteExpiryJob's "never started" sweep - PUBLISHED/FULL routes whose departure has passed by more than the grace window. */
    List<RoutePost> findByStatusInAndDepartureTimeBefore(List<RouteStatus> statuses, Instant before);

    /** RouteDepartureReminderJob's sweep - PUBLISHED/FULL routes departing within the reminder
     * window that haven't been reminded yet. */
    List<RoutePost> findByStatusInAndDepartureTimeBetweenAndReminderSentAtIsNull(
            List<RouteStatus> statuses, Instant windowStart, Instant windowEnd);

    /** Atomic claim on the reminder, same shape as lockOnFirstBooking - the "where reminder_sent_at
     * is null" guard means a route can only ever be claimed once, even if the sweep somehow overlaps
     * itself across runs. Its own transaction - RouteDepartureReminderJob calls it outside one, and the
     * claim has to commit before the reminder goes out anyway. */
    @Transactional
    @Modifying
    @Query("update RoutePost r set r.reminderSentAt = :now where r.id = :id and r.reminderSentAt is null")
    int markReminderSent(@Param("id") String id, @Param("now") Instant now);

    /** The driver's get-ready sweep - routes entering the active window that haven't had it yet. */
    List<RoutePost> findByStatusInAndDepartureTimeBetweenAndDriverReminderSentAtIsNull(
            List<RouteStatus> statuses, Instant windowStart, Instant windowEnd);

    /** Departure has passed and the driver still hasn't started - the "start or cancel" sweep. */
    List<RoutePost> findByStatusInAndDepartureTimeBetweenAndStartNudgeSentAtIsNull(
            List<RouteStatus> statuses, Instant windowStart, Instant windowEnd);

    /** Same once-only claim as markReminderSent. */
    @Transactional
    @Modifying
    @Query("update RoutePost r set r.driverReminderSentAt = :now where r.id = :id and r.driverReminderSentAt is null")
    int markDriverReminderSent(@Param("id") String id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update RoutePost r set r.startNudgeSentAt = :now where r.id = :id and r.startNudgeSentAt is null")
    int markStartNudgeSent(@Param("id") String id, @Param("now") Instant now);
}

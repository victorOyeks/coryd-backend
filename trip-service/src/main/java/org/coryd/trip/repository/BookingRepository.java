package org.coryd.trip.repository;

import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByRoutePostId(String routePostId);

    /** RouteDepartureReminderJob - who to actually push the "15 minutes to departure" reminder to. */
    List<Booking> findByRoutePostIdAndStatus(String routePostId, BookingStatus status);

    /** Dev route reset only - see RouteResetService. */
    long deleteByRoutePostId(String routePostId);
    List<Booking> findByRoutePostIdIn(List<String> routePostIds);
    long countByRoutePostIdInAndStatus(List<String> routePostIds, BookingStatus status);
    List<Booking> findTop5ByRiderIdAndConfirmedAtIsNotNullOrderByCreatedAtDesc(String riderId);
    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant before);

    /** Compare-and-set on status - 0 means another writer moved the booking first, so the caller's
     * side effects (seat release, refund, confirmation) must not run. */
    @Modifying(flushAutomatically = true)
    @Query("update Booking b set b.status = :to, b.updatedAt = CURRENT_TIMESTAMP where b.id = :id and b.status in :from")
    int transitionStatus(@Param("id") String id, @Param("from") Collection<BookingStatus> from, @Param("to") BookingStatus to);

    /** Serializes a user's bookings and route posts for the rest of the transaction - two taps landing
     * at once would otherwise both pass the overlap check. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtext(:riderId))) l", nativeQuery = true)
    Integer lockUser(@Param("riderId") String userId);

    List<Booking> findByRiderIdAndStatus(String riderId, BookingStatus status);

    List<Booking> findByRiderIdAndStatusIn(String riderId, Collection<BookingStatus> statuses);

    /** Spends one pickup-code attempt before the code is compared, so parallel guesses can't
     * outrun the cap - 0 means the attempts are used up. */
    @Modifying(flushAutomatically = true)
    @Query("update Booking b set b.pickupCodeAttempts = b.pickupCodeAttempts + 1 where b.id = :id and b.pickupCodeAttempts < :max")
    int spendPickupCodeAttempt(@Param("id") String id, @Param("max") int max);

    @Modifying(flushAutomatically = true)
    @Query("update Booking b set b.pickupCodeAttempts = 0 where b.id = :id")
    int resetPickupCodeAttempts(@Param("id") String id);

    boolean existsByRoutePostIdAndPickupCodeAndStatusIn(String routePostId, String pickupCode, Collection<BookingStatus> statuses);


    /** Powers the Home screen's "Go back to your current trip" banner. */
    Optional<Booking> findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(String riderId, List<BookingStatus> statuses);

    /** reserveSeats()'s same-route duplicate-claim guard - a rider with an unanswered RESERVED
     * booking on this exact route must resolve it via accept/decline, not book a second, fresh seat
     * on the same route out from under it (ACTIVE_TRIP_STATUSES doesn't cover this - RESERVED is
     * deliberately excluded from that list so an unanswered offer never blocks booking elsewhere). */
    Optional<Booking> findFirstByRiderIdAndRoutePostIdAndStatus(String riderId, String routePostId, BookingStatus status);

    boolean existsByRiderIdAndRoutePostIdAndStatusIn(String riderId, String routePostId, Collection<BookingStatus> statuses);

    /** Trip History screen - search matches the "origin -> destination" snapshot, so it works without joining route_posts. */
    @Query("""
            select b from Booking b
            where b.riderId = :riderId
              and b.status in :statuses
              and b.confirmedAt is not null
              and (lower(b.routeSummarySnapshot) like lower(concat('%', :search, '%')) or cast(:search as string) is null)
            order by b.createdAt desc
            """)
    Page<Booking> findHistory(@Param("riderId") String riderId, @Param("statuses") List<BookingStatus> statuses,
                               @Param("search") String search, Pageable pageable);

    /** Dashboard's lifetime "Total Trips"/"Reliability" tiles - one query for both counts (not two),
     * joined against route_posts since Booking has no JPA relation to RoutePost, only a plain
     * routePostId column. */
    @Query(value = """
            select
                count(*) filter (where b.status = 'COMPLETED') as completed,
                count(*) filter (where b.status = 'CANCELLED_DRIVER' and b.confirmed_at is not null) as cancelledDriver
            from trip.bookings b
            join trip.route_posts r on b.route_post_id = r.id
            where r.driver_id = :driverId
            """, nativeQuery = true)
    DriverTripCounts countLifetimeTripsForDriver(@Param("driverId") String driverId);

    interface DriverTripCounts {
        Long getCompleted();
        Long getCancelledDriver();
    }
}

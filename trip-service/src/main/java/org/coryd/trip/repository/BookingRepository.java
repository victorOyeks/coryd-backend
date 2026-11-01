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

    List<Booking> findTop50ByRiderIdAndStatusInOrderByCreatedAtDesc(String riderId, Collection<BookingStatus> statuses);

    /** Of these riders, the ones already holding a seat on a trip leaving between from and to - they have a ride. */
    @Query("""
            select distinct b.riderId from Booking b, RoutePost r
            where r.id = b.routePostId and b.riderId in :riderIds and b.status in :statuses
              and r.departureTime between :from and :to
            """)
    List<String> ridersWithASeatBetween(@Param("riderIds") Collection<String> riderIds, @Param("statuses") Collection<BookingStatus> statuses,
                                        @Param("from") Instant from, @Param("to") Instant to);

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

    /** countLifetimeTripsForDriver for many drivers at once - drivers with no bookings are left out. */
    @Query(value = """
            select r.driver_id as driverId,
                count(*) filter (where b.status = 'COMPLETED') as completed,
                count(*) filter (where b.status = 'CANCELLED_DRIVER' and b.confirmed_at is not null) as cancelledDriver
            from trip.bookings b
            join trip.route_posts r on b.route_post_id = r.id
            where r.driver_id in (:driverIds)
            group by r.driver_id
            """, nativeQuery = true)
    List<DriverTripCountsById> countLifetimeTripsForDrivers(@Param("driverIds") Collection<String> driverIds);

    interface DriverTripCountsById {
        String getDriverId();
        Long getCompleted();
        Long getCancelledDriver();
    }

    /** Problems riders reported on trips these drivers drove - not the drivers' own reports. */
    @Query(value = """
            select r.driver_id as driverId, count(*) as reports
            from trip.issue_reports i
            join trip.bookings b on b.id = i.booking_id
            join trip.route_posts r on r.id = b.route_post_id
            where r.driver_id in (:driverIds) and i.reported_by <> r.driver_id
            group by r.driver_id
            """, nativeQuery = true)
    List<DriverReportCount> countReportsAgainstDrivers(@Param("driverIds") Collection<String> driverIds);

    interface DriverReportCount {
        String getDriverId();
        Long getReports();
    }

    /**
     * The Riders Directory's numbers for many riders at once: trips completed, paid seats they cancelled (not those ops
     * cancelled by suspending them) or didn't show up for, and what their completed trips cost them. Riders with no
     * bookings are left out.
     */
    @Query(value = "select b.rider_id as riderId,"
            + " count(*) filter (where b.status = 'COMPLETED') as completed,"
            + " count(*) filter (where b.status = 'CANCELLED_RIDER' and b.confirmed_at is not null"
            + "   and not exists (select 1 from trip.booking_status_history h"
            + "                   where h.booking_id = b.id and h.note like '" + org.coryd.trip.service.BookingService.SUSPENDED_NOTE + "%')) as cancelledRider,"
            + " count(*) filter (where b.status = 'NO_SHOW') as noShow,"
            + " coalesce(sum(b.fare_total) filter (where b.status = 'COMPLETED'), 0) as spent"
            + " from trip.bookings b"
            + " where b.rider_id in (:riderIds)"
            + " group by b.rider_id", nativeQuery = true)
    List<RiderTripCounts> countTripsForRiders(@Param("riderIds") Collection<String> riderIds);

    interface RiderTripCounts {
        String getRiderId();
        Long getCompleted();
        Long getCancelledRider();
        Long getNoShow();
        java.math.BigDecimal getSpent();
    }

    /** Problems drivers reported on these riders' bookings - not the riders' own reports. */
    @Query(value = """
            select b.rider_id as riderId, count(*) as reports
            from trip.issue_reports i
            join trip.bookings b on b.id = i.booking_id
            where b.rider_id in (:riderIds) and i.reported_by <> b.rider_id
            group by b.rider_id
            """, nativeQuery = true)
    List<RiderReportCount> countReportsAgainstRiders(@Param("riderIds") Collection<String> riderIds);

    interface RiderReportCount {
        String getRiderId();
        Long getReports();
    }

    /**
     * The Ops Console's trip history for one rider: paid bookings, newest trip first. A trip matches when text (lower case,
     * "%...%", or "" for none) matches the route, its ends or the driver, or - when hasDate is 1 - its date in Lagos
     * matches year/month/day/isodow (0 for any). Either, so "Sunday" finds a driver called Sunday as well as Sunday trips.
     * departure_time is stored in the JVM's zone; shiftSeconds moves it to Lagos.
     */
    @Query(value = """
            select b.* from trip.bookings b
            join trip.route_posts r on r.id = b.route_post_id
            where b.rider_id = :riderId and b.confirmed_at is not null
              and ((:text = '' and :hasDate = 0)
                   or (:text <> '' and (lower(coalesce(b.route_summary_snapshot, '')) like :text or lower(r.origin_label) like :text
                        or lower(r.destination_label) like :text or lower(coalesce(b.driver_name_snapshot, '')) like :text))
                   or (:hasDate = 1
                        and (:year = 0 or extract(year from r.departure_time + make_interval(secs => :shiftSeconds)) = :year)
                        and (:month = 0 or extract(month from r.departure_time + make_interval(secs => :shiftSeconds)) = :month)
                        and (:day = 0 or extract(day from r.departure_time + make_interval(secs => :shiftSeconds)) = :day)
                        and (:isodow = 0 or extract(isodow from r.departure_time + make_interval(secs => :shiftSeconds)) = :isodow)))
            order by r.departure_time desc, b.id
            """, countQuery = """
            select count(*) from trip.bookings b
            join trip.route_posts r on r.id = b.route_post_id
            where b.rider_id = :riderId and b.confirmed_at is not null
              and ((:text = '' and :hasDate = 0)
                   or (:text <> '' and (lower(coalesce(b.route_summary_snapshot, '')) like :text or lower(r.origin_label) like :text
                        or lower(r.destination_label) like :text or lower(coalesce(b.driver_name_snapshot, '')) like :text))
                   or (:hasDate = 1
                        and (:year = 0 or extract(year from r.departure_time + make_interval(secs => :shiftSeconds)) = :year)
                        and (:month = 0 or extract(month from r.departure_time + make_interval(secs => :shiftSeconds)) = :month)
                        and (:day = 0 or extract(day from r.departure_time + make_interval(secs => :shiftSeconds)) = :day)
                        and (:isodow = 0 or extract(isodow from r.departure_time + make_interval(secs => :shiftSeconds)) = :isodow)))
            """, nativeQuery = true)
    org.springframework.data.domain.Page<Booking> findRiderTripsForOps(@Param("riderId") String riderId, @Param("text") String text,
                                                                      @Param("year") int year, @Param("month") int month,
                                                                      @Param("day") int day, @Param("isodow") int isodow,
                                                                      @Param("hasDate") int hasDate,
                                                                      @Param("shiftSeconds") int shiftSeconds,
                                                                      org.springframework.data.domain.Pageable pageable);

    /** Everyone who has ever booked, a page at a time - the nightly rider stats sync. */
    @Query("select distinct b.riderId from Booking b order by b.riderId")
    org.springframework.data.domain.Slice<String> findDistinctRiderIds(org.springframework.data.domain.Pageable pageable);

    interface DriverTripCounts {
        Long getCompleted();
        Long getCancelledDriver();
    }
}

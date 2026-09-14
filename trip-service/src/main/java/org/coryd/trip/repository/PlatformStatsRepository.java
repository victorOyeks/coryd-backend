package org.coryd.trip.repository;

import org.coryd.trip.entity.Booking;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Platform-wide aggregates for the ops reports - every query is windowed by an explicit
 * [from, to) range so the same methods serve the daily, weekly and monthly jobs.
 */
public interface PlatformStatsRepository extends Repository<Booking, String> {

    interface RouteCounts {
        long getPosted();
        long getCompleted();
        long getCancelled();
        Long getSeatsOffered();
        long getActiveDrivers();
    }

    interface BookingCounts {
        long getConfirmed();
        long getCompleted();
        long getCancelledRider();
        long getCancelledDriver();
        long getNoShows();
        Long getSeatsBooked();
    }

    interface RiderCounts {
        long getDistinctRiders();
        long getRepeatRiders();
    }

    interface TopRouteRow {
        String getOriginLabel();
        String getDestinationLabel();
        long getSeats();
        BigDecimal getGrossFare();
    }

    @Query(value = """
            select
                count(*)                                                    as posted,
                count(*) filter (where status = 'COMPLETED')                as completed,
                count(*) filter (where status = 'CANCELLED')                as cancelled,
                coalesce(sum(seat_capacity), 0)                             as seatsOffered,
                count(distinct driver_id)                                   as activeDrivers
            from trip.route_posts
            where created_at >= :from and created_at < :to
            """, nativeQuery = true)
    RouteCounts routeCounts(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * HELD/RESERVED are payment-pending, never real bookings - excluded from every count.
     * Terminal states are counted on updated_at since a booking cancelled today may well
     * have been created last week.
     */
    @Query(value = """
            select
                count(*) filter (where created_at >= :from and created_at < :to
                                   and status not in ('HELD', 'RESERVED'))              as confirmed,
                count(*) filter (where updated_at >= :from and updated_at < :to
                                   and status = 'COMPLETED')                            as completed,
                count(*) filter (where updated_at >= :from and updated_at < :to
                                   and status = 'CANCELLED_RIDER')                      as cancelledRider,
                count(*) filter (where updated_at >= :from and updated_at < :to
                                   and status = 'CANCELLED_DRIVER')                     as cancelledDriver,
                count(*) filter (where updated_at >= :from and updated_at < :to
                                   and status = 'NO_SHOW')                              as noShows,
                coalesce(sum(seat_count) filter (where created_at >= :from and created_at < :to
                                   and status not in ('HELD', 'RESERVED')), 0)          as seatsBooked
            from trip.bookings
            """, nativeQuery = true)
    BookingCounts bookingCounts(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select
                count(*)                              as distinctRiders,
                count(*) filter (where bookings >= 2) as repeatRiders
            from (
                select rider_id, count(*) as bookings
                from trip.bookings
                where created_at >= :from and created_at < :to
                  and status not in ('HELD', 'RESERVED')
                group by rider_id
            ) per_rider
            """, nativeQuery = true)
    RiderCounts riderCounts(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select
                r.origin_label              as originLabel,
                r.destination_label         as destinationLabel,
                sum(b.seat_count)           as seats,
                sum(b.fare_total)           as grossFare
            from trip.bookings b
            join trip.route_posts r on r.id = b.route_post_id
            where b.created_at >= :from and b.created_at < :to
              and b.status not in ('HELD', 'RESERVED')
            group by r.origin_label, r.destination_label
            order by seats desc
            limit :limit
            """, nativeQuery = true)
    List<TopRouteRow> topRoutes(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
}

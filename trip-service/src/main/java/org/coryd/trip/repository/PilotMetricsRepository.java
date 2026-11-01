package org.coryd.trip.repository;

import org.coryd.trip.entity.RoutePost;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** The pilot numbers, each worked out by the database - one aggregate query, never the window's rows. */
public interface PilotMetricsRepository extends Repository<RoutePost, String> {

    interface SearchTotals {
        long getSearches();
        long getWithResults();
        long getRiders();
    }

    @Query(value = """
            select count(*) as searches,
                   count(*) filter (where s.result_count > 0) as withResults,
                   count(distinct s.rider_id) as riders
            from trip.route_searches s
            where s.created_at > :from
            """, nativeQuery = true)
    SearchTotals searchTotals(@Param("from") Instant from);

    /** Riders who searched in the window and took a seat on a trip that departed in it. */
    @Query(value = """
            select count(distinct b.rider_id)
            from trip.bookings b
            join trip.route_posts r on r.id = b.route_post_id
            where r.status in (:routeStatuses) and r.departure_time between :from and :to
              and b.status in (:seatStatuses)
              and exists (select 1 from trip.route_searches s where s.rider_id = b.rider_id and s.created_at > :from)
            """, nativeQuery = true)
    long ridersWhoSearchedAndBooked(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("routeStatuses") Collection<String> routeStatuses, @Param("seatStatuses") Collection<String> seatStatuses);

    interface OfferedTotals {
        long getRoutes();
        long getSeatsOffered();
        long getSeatsBooked();
        long getLeavingNowRoutes();
        long getLeavingNowSeats();
    }

    /** Trips that ran or could still run in the window, their seats, and those posted inside the half hour before leaving. */
    @Query(value = """
            select count(*) as routes,
                   coalesce(sum(r.seat_capacity), 0) as seatsOffered,
                   coalesce(sum(t.seats), 0) as seatsBooked,
                   count(*) filter (where r.created_at > r.departure_time - interval '30 minutes') as leavingNowRoutes,
                   coalesce(sum(t.seats) filter (where r.created_at > r.departure_time - interval '30 minutes'), 0) as leavingNowSeats
            from trip.route_posts r
            left join (select b.route_post_id, sum(b.seat_count) as seats
                       from trip.bookings b where b.status in (:seatStatuses) group by b.route_post_id) t on t.route_post_id = r.id
            where r.status in (:routeStatuses) and r.departure_time between :from and :to
            """, nativeQuery = true)
    OfferedTotals offeredTotals(@Param("from") Instant from, @Param("to") Instant to,
                                @Param("routeStatuses") Collection<String> routeStatuses, @Param("seatStatuses") Collection<String> seatStatuses);

    interface Repeat {
        long getLastWeek();
        long getBack();
    }

    /** Riders who completed a trip in [lastFrom, from), and how many of them completed another in [from, to). */
    @Query(value = """
            with last_week as (
                select distinct b.rider_id from trip.bookings b join trip.route_posts r on r.id = b.route_post_id
                where r.status = 'COMPLETED' and b.status = 'COMPLETED' and r.departure_time between :lastFrom and :from),
            this_window as (
                select distinct b.rider_id from trip.bookings b join trip.route_posts r on r.id = b.route_post_id
                where r.status = 'COMPLETED' and b.status = 'COMPLETED' and r.departure_time between :from and :to)
            select (select count(*) from last_week) as lastWeek,
                   (select count(*) from last_week l where l.rider_id in (select rider_id from this_window)) as back
            """, nativeQuery = true)
    Repeat repeat(@Param("lastFrom") Instant lastFrom, @Param("from") Instant from, @Param("to") Instant to);

    interface SearchedRoute {
        String getOriginLabel();
        String getDestinationLabel();
        long getSearches();
        long getRiders();
        long getNoResults();
    }

    /** By the place names riders picked, trimmed and lower-cased - most riders first. */
    @Query(value = """
            select min(s.origin_label) as originLabel, min(s.destination_label) as destinationLabel,
                   count(*) as searches, count(distinct s.rider_id) as riders,
                   count(*) filter (where s.result_count is null or s.result_count = 0) as noResults
            from trip.route_searches s
            where s.created_at > :from
            group by coalesce(lower(trim(s.origin_label)), '(unnamed)'), coalesce(lower(trim(s.destination_label)), '(unnamed)')
            order by riders desc, searches desc
            limit 10
            """, nativeQuery = true)
    List<SearchedRoute> topSearchedRoutes(@Param("from") Instant from);

    interface Waits {
        long getWaited();
        long getFound();
    }

    /** One-off "notify me" made for about the moment it was made (within half an hour) - waiting, not planning. */
    @Query(value = """
            select count(*) as waited, count(*) filter (where n.fulfilled) as found
            from trip.notify_me_requests n
            where n.created_at > :from and n.created_at < :to and n.desired_departure_time is not null
              and abs(extract(epoch from (n.desired_departure_time - n.created_at))) <= 1800
            """, nativeQuery = true)
    Waits waits(@Param("from") Instant from, @Param("to") Instant to);

    interface UnmetArea {
        String getDestinationLabel();
        long getRiders();
    }

    /** Where those waits went unanswered and are over - most riders first. */
    @Query(value = """
            select coalesce(lower(trim(n.destination_label)), '(unnamed)') as destinationLabel, count(distinct n.rider_id) as riders
            from trip.notify_me_requests n
            where n.created_at > :from and n.created_at < :to and n.desired_departure_time is not null
              and abs(extract(epoch from (n.desired_departure_time - n.created_at))) <= 1800
              and not n.fulfilled and not n.cancelled and n.expires_at <= :now
            group by 1
            order by riders desc, destinationLabel asc
            limit 10
            """, nativeQuery = true)
    List<UnmetArea> unmetAreas(@Param("from") Instant from, @Param("to") Instant to, @Param("now") Instant now);
}

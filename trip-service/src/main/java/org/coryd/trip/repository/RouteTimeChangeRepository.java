package org.coryd.trip.repository;

import org.coryd.trip.entity.RouteTimeChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RouteTimeChangeRepository extends JpaRepository<RouteTimeChange, String> {

    List<RouteTimeChange> findByRoutePostIdOrderByCreatedAtAsc(String routePostId);

    long countByCreatedAtAfter(Instant since);

    long countByCreatedAtAfterAndLastMinuteTrue(Instant since);

    interface DriverTimeChanges {
        String getDriverId();
        long getLastMinuteChanges();
        long getTotalChanges();
        Instant getLastChangedAt();
    }

    /** Drivers who changed trips riders had booked at the last minute at least minLastMinute times since from - most first. */
    @Query("""
            select c.driverId as driverId,
                   sum(case when c.lastMinute = true then 1 else 0 end) as lastMinuteChanges,
                   count(c) as totalChanges,
                   max(c.createdAt) as lastChangedAt
            from RouteTimeChange c
            where c.createdAt >= :from
            group by c.driverId
            having sum(case when c.lastMinute = true then 1 else 0 end) >= :minLastMinute
            order by sum(case when c.lastMinute = true then 1 else 0 end) desc, max(c.createdAt) desc
            """)
    List<DriverTimeChanges> frequentLastMinuteChangers(@Param("from") Instant from, @Param("minLastMinute") long minLastMinute);
}

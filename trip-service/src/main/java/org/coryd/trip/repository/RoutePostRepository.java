package org.coryd.trip.repository;

import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.entity.RoutePost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RoutePostRepository extends JpaRepository<RoutePost, String> {

    List<RoutePost> findByDriverId(String driverId);

    @Query("""
            select r from RoutePost r
            where r.status = 'PUBLISHED'
              and r.seatsAvailable > 0
              and r.departureTime between :earliest and :latest
              and r.originLat between :originLatMin and :originLatMax
              and r.originLng between :originLngMin and :originLngMax
              and r.destinationLat between :destLatMin and :destLatMax
              and r.destinationLng between :destLngMin and :destLngMax
            order by r.departureTime asc
            """)
    List<RoutePost> search(@Param("earliest") Instant earliest,
                            @Param("latest") Instant latest,
                            @Param("originLatMin") double originLatMin,
                            @Param("originLatMax") double originLatMax,
                            @Param("originLngMin") double originLngMin,
                            @Param("originLngMax") double originLngMax,
                            @Param("destLatMin") double destLatMin,
                            @Param("destLatMax") double destLatMax,
                            @Param("destLngMin") double destLngMin,
                            @Param("destLngMax") double destLngMax);

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

    @Modifying
    @Query("""
            update RoutePost r
            set r.seatsAvailable = r.seatsAvailable + :seats
            where r.id = :id
            """)
    int releaseSeats(@Param("id") String id, @Param("seats") int seats);

    @Modifying
    @Query("update RoutePost r set r.status = 'FULL' where r.id = :id and r.seatsAvailable = 0")
    int markFullIfExhausted(@Param("id") String id);

    @Modifying
    @Query("update RoutePost r set r.firstBookingLockedAt = :now where r.id = :id and r.firstBookingLockedAt is null")
    int lockOnFirstBooking(@Param("id") String id, @Param("now") Instant now);

    List<RoutePost> findByStatusAndDepartureTimeBefore(RouteStatus status, Instant before);
}

package org.coryd.trip.repository;

import org.coryd.trip.entity.RouteSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RouteSearchRepository extends JpaRepository<RouteSearch, String> {

    List<RouteSearch> findByCreatedAtAfter(Instant since);

    /** Recent searches starting inside a box, for leaving now - the caller checks the exact distance. */
    @Query("""
            select s from RouteSearch s
            where s.createdAt >= :since
              and s.desiredDepartureTime between :from and :to
              and s.originLat between :minLat and :maxLat and s.originLng between :minLng and :maxLng
            """)
    List<RouteSearch> findRecentStartingIn(@Param("since") Instant since, @Param("from") Instant from, @Param("to") Instant to,
                                           @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                           @Param("minLng") double minLng, @Param("maxLng") double maxLng);

    @Modifying
    @Query("delete from RouteSearch s where s.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

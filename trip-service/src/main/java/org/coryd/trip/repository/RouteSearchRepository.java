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

    @Modifying
    @Query("delete from RouteSearch s where s.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

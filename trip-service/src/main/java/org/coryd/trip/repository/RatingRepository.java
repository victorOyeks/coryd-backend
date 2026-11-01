package org.coryd.trip.repository;

import org.coryd.common.enums.RatingDirection;
import org.coryd.trip.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, String> {
    List<Rating> findByBookingId(String bookingId);

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
    List<Rating> findByRateeId(String rateeId);

    boolean existsByBookingIdAndDirection(String bookingId, RatingDirection direction);

    /** The Trip Receipt's per-passenger stars - one query for every booking on the route, not one per passenger. A booking can have a Rating row in each direction; this narrows to just the one the receipt cares about. */
    List<Rating> findByBookingIdInAndDirection(List<String> bookingIds, RatingDirection direction);

    /** One aggregate query for a whole search-result page, not one per driver. */
    @Query("""
            select r.rateeId as rateeId, avg(r.stars) as avgStars, count(r) as ratingCount
            from Rating r
            where r.rateeId in :rateeIds
            group by r.rateeId
            """)
    List<RatingSummary> summarize(@Param("rateeIds") List<String> rateeIds);

    /** summarize() for ratings given in one direction - a rider's stars from drivers, not any they got as a driver. */
    @Query("""
            select r.rateeId as rateeId, avg(r.stars) as avgStars, count(r) as ratingCount
            from Rating r
            where r.rateeId in :rateeIds and r.direction = :direction
            group by r.rateeId
            """)
    List<RatingSummary> summarize(@Param("rateeIds") List<String> rateeIds, @Param("direction") RatingDirection direction);

    interface RatingSummary {
        String getRateeId();
        Double getAvgStars();
        Long getRatingCount();
    }
}

package org.coryd.trip.repository;

import org.coryd.common.enums.RatingDirection;
import org.coryd.trip.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, String>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Rating> {
    List<Rating> findByBookingId(String bookingId);

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
    List<Rating> findByRateeId(String rateeId);

    boolean existsByBookingIdAndDirection(String bookingId, RatingDirection direction);

    /** The Trip Receipt's per-passenger stars - one query for every booking on the route, not one per passenger. A booking can have a Rating row in each direction; this narrows to just the one the receipt cares about. */
    List<Rating> findByBookingIdInAndDirection(List<String> bookingIds, RatingDirection direction);

    /** One aggregate query for a whole search-result page, not one per driver. Ratings ops hid count in no average. */
    @Query("""
            select r.rateeId as rateeId, avg(r.stars) as avgStars, count(r) as ratingCount
            from Rating r
            where r.rateeId in :rateeIds and r.hiddenAt is null
            group by r.rateeId
            """)
    List<RatingSummary> summarize(@Param("rateeIds") List<String> rateeIds);

    /** summarize() for ratings given in one direction - a rider's stars from drivers, not any they got as a driver. */
    @Query("""
            select r.rateeId as rateeId, avg(r.stars) as avgStars, count(r) as ratingCount
            from Rating r
            where r.rateeId in :rateeIds and r.direction = :direction and r.hiddenAt is null
            group by r.rateeId
            """)
    List<RatingSummary> summarize(@Param("rateeIds") List<String> rateeIds, @Param("direction") RatingDirection direction);

    /** Visible stars given to one person, 1-5 with how many of each - the console's distribution bar. */
    @Query("""
            select r.stars as stars, count(r) as ratingCount
            from Rating r
            where r.rateeId = :rateeId and r.hiddenAt is null and (:direction is null or r.direction = :direction)
            group by r.stars
            """)
    List<StarCount> distribution(@Param("rateeId") String rateeId, @Param("direction") RatingDirection direction);

    @Query("""
            select count(r) from Rating r
            where r.rateeId = :rateeId and r.hiddenAt is not null and (:direction is null or r.direction = :direction)
            """)
    long countHidden(@Param("rateeId") String rateeId, @Param("direction") RatingDirection direction);

    interface StarCount {
        Integer getStars();
        Long getRatingCount();
    }

    interface RatingSummary {
        String getRateeId();
        Double getAvgStars();
        Long getRatingCount();
    }
}

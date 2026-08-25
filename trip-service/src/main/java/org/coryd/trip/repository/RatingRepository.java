package org.coryd.trip.repository;

import org.coryd.common.enums.RatingDirection;
import org.coryd.trip.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, String> {
    List<Rating> findByBookingId(String bookingId);
    List<Rating> findByRateeId(String rateeId);

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

    interface RatingSummary {
        String getRateeId();
        Double getAvgStars();
        Long getRatingCount();
    }
}

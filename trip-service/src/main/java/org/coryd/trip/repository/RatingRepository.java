package org.coryd.trip.repository;

import org.coryd.trip.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, String> {
    List<Rating> findByBookingId(String bookingId);
    List<Rating> findByRateeId(String rateeId);
}

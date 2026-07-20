package org.coryd.trip.repository;

import org.coryd.trip.entity.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, String> {
    List<BookingStatusHistory> findByBookingIdOrderByCreatedAtAsc(String bookingId);
}

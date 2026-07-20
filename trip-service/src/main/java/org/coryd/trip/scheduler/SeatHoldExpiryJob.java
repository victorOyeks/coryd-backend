package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * R-306 / Section 10.3: release HELD bookings whose payment never completed
 * within the hold window, so the seat goes back into inventory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldExpiryJob {

    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void releaseExpiredHolds() {
        List<Booking> expired = bookingRepository.findByStatusAndHoldExpiresAtBefore(BookingStatus.HELD, Instant.now());
        for (Booking booking : expired) {
            routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());
            booking.setStatus(BookingStatus.CANCELLED_RIDER);
            bookingRepository.save(booking);
        }
        if (!expired.isEmpty()) {
            log.info("Released {} expired seat holds", expired.size());
        }
    }
}

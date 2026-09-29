package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.service.BookingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * R-306 / Section 10.3: release HELD bookings whose payment never completed within the hold
 * window, so the seat goes back into inventory. Also sweeps RESERVED bookings (a driver-offered
 * seat a favourite rider never responded to) the same way - same mechanics, same outcome, just a
 * different status and a longer window (RouteService.RESERVATION_HOLD_MINUTES, 15 min, vs
 * BookingService.SEAT_HOLD_MINUTES, 4 min) - not different enough to warrant a second job class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldExpiryJob {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    /** Each booking is released in its own transaction by a compare-and-set, so one that paid a
     * moment before the sweep reached it stays confirmed, and one bad row doesn't stop the rest. */
    @Scheduled(fixedDelay = 60_000)
    public void releaseExpiredHolds() {
        release(bookingRepository.findByStatusAndHoldExpiresAtBefore(BookingStatus.HELD, Instant.now()), "hold");
        release(bookingRepository.findByStatusAndHoldExpiresAtBefore(BookingStatus.RESERVED, Instant.now()), "reservation");
    }

    private void release(List<Booking> expired, String kind) {
        for (Booking booking : expired) {
            try {
                bookingService.expireUnpaid(booking);
            } catch (Exception e) {
                log.error("Failed to release expired {} {}: {}", kind, booking.getId(), e.getMessage(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Swept {} expired {}(s)", expired.size(), kind);
        }
    }
}

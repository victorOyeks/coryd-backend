package org.coryd.trip.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.service.RiderStatsPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.Set;

/**
 * A booking reaching an end - completed, cancelled or missed - changes its rider's numbers in the Riders Directory,
 * whichever code path put it there. Hibernate builds this through Spring; the publisher is looked up when needed.
 */
public class BookingRiderStatsListener {

    private static final Set<BookingStatus> ENDED = EnumSet.of(BookingStatus.COMPLETED, BookingStatus.CANCELLED_RIDER,
            BookingStatus.CANCELLED_DRIVER, BookingStatus.NO_SHOW);

    @Autowired
    private ObjectProvider<RiderStatsPublisher> publisher;

    @PostPersist
    @PostUpdate
    void changed(Booking booking) {
        RiderStatsPublisher target = publisher == null ? null : publisher.getIfAvailable();
        if (target != null && booking.getStatus() != null && ENDED.contains(booking.getStatus())) {
            target.changed(booking.getRiderId());
        }
    }
}

package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.trip.client.PaymentDevClient;
import org.coryd.trip.dto.RouteResetResult;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Puts a route back to the state it was in the moment the driver posted it, so the same route
 * can be taken through book -> start -> complete again without creating a new one. Destructive
 * by design: the bookings made against it, and everything hanging off those bookings, are
 * deleted rather than cancelled, because a cancelled booking still shows up in rider history
 * and still counts against the seat maths.
 *
 * Never registered unless coryd.dev-tools.enabled is true - this must not exist in production.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class RouteResetService {

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRepository bookingStatusHistoryRepository;
    private final RatingRepository ratingRepository;
    private final IssueReportRepository issueReportRepository;
    private final SosIncidentRepository sosIncidentRepository;
    private final ChatService chatService;
    private final PaymentDevClient paymentDevClient;
    private final org.coryd.trip.repository.CancellationLogRepository cancellationLogRepository;

    @Transactional
    public RouteResetResult reset(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("No route post with id " + routePostId));

        List<String> bookingIds = bookingRepository.findByRoutePostId(routePostId).stream()
                .map(Booking::getId)
                .toList();

        long statusHistoryDeleted = 0;
        long ratingsDeleted = 0;
        long issueReportsDeleted = 0;
        long sosIncidentsDeleted = 0;
        if (!bookingIds.isEmpty()) {
            statusHistoryDeleted = bookingStatusHistoryRepository.deleteByBookingIdIn(bookingIds);
            ratingsDeleted = ratingRepository.deleteByBookingIdIn(bookingIds);
            issueReportsDeleted = issueReportRepository.deleteByBookingIdIn(bookingIds);
            sosIncidentsDeleted = sosIncidentRepository.deleteByBookingIdIn(bookingIds);
        }
        bookingRepository.deleteByRoutePostId(routePostId);
        // A reset route was never cancelled - nor were its seats.
        cancellationLogRepository.deleteByRoutePostId(routePostId);
        long chatMessagesDeleted = chatService.deleteChatForRoute(routePostId);

        RouteStatus previousStatus = route.getStatus();
        route.setStatus(RouteStatus.PUBLISHED);
        route.setSeatsAvailable(route.getSeatCapacity());
        route.setFirstBookingLockedAt(null);
        route.setStartedAt(null);
        route.setCompletedAt(null);
        routePostRepository.save(route);

        // Inside the transaction on purpose - if payment-service rejects the purge, everything
        // above rolls back rather than leaving the two services disagreeing.
        String paymentRecordsPurged = paymentDevClient.purgeBookings(bookingIds);

        RouteResetResult result = new RouteResetResult(
                routePostId, previousStatus.name(), route.getSeatCapacity(), bookingIds.size(),
                statusHistoryDeleted, ratingsDeleted, issueReportsDeleted, sosIncidentsDeleted,
                chatMessagesDeleted, paymentRecordsPurged);
        log.warn("DEV route reset: {}", result);
        return result;
    }
}

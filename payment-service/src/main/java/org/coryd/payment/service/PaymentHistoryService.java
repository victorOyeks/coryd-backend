package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.dto.DriverStatementItem;
import org.coryd.payment.dto.RiderPaymentItem;
import org.coryd.payment.repository.PaymentHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/** Everything money-related for one person, newest first. Route text and rider names are added once per page. */
@Service
@RequiredArgsConstructor
public class PaymentHistoryService {

    private final PaymentHistoryRepository paymentHistoryRepository;
    private final TripServiceClient tripServiceClient;
    private final ProfileServiceClient profileServiceClient;

    /** A rider's trip payments and refunds back to their card. */
    @Transactional(readOnly = true)
    public PagedResponse<RiderPaymentItem> riderHistory(String riderId, Pageable pageable, String callerId, UserRole callerRole) {
        requireSelfOrOps(riderId, callerId, callerRole);
        Page<RiderPaymentItem> page = paymentHistoryRepository.riderHistory(riderId, pageable);
        Map<String, TripServiceClient.BookingRoute> routes = tripServiceClient.fetchBookingRoutes(
                page.getContent().stream().map(RiderPaymentItem::bookingId).filter(Objects::nonNull).distinct().toList());
        return PagedResponse.from(page.map(item -> {
            TripServiceClient.BookingRoute route = item.bookingId() == null ? null : routes.get(item.bookingId());
            return route == null ? item : item.withRoute(new RiderPaymentItem.Route(route.routePostId(), route.originLabel(),
                    route.destinationLabel(), route.departureTime()));
        }));
    }

    /** A driver's trips earned, payouts, and every change to what they owe from refunded trips. */
    @Transactional(readOnly = true)
    public PagedResponse<DriverStatementItem> driverStatement(String driverId, Pageable pageable, String callerId, UserRole callerRole) {
        requireSelfOrOps(driverId, callerId, callerRole);
        Page<DriverStatementItem> page = paymentHistoryRepository.driverStatement(driverId, pageable);
        Map<String, TripServiceClient.BookingRoute> routes = tripServiceClient.fetchBookingRoutes(
                page.getContent().stream().map(DriverStatementItem::bookingId).filter(Objects::nonNull).distinct().toList());
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = profileServiceClient.fetchRiderSummaries(
                page.getContent().stream().map(DriverStatementItem::rider).filter(Objects::nonNull)
                        .map(DriverStatementItem.Rider::riderId).distinct().toList());
        return PagedResponse.from(page.map(item -> {
            TripServiceClient.BookingRoute route = item.bookingId() == null ? null : routes.get(item.bookingId());
            DriverStatementItem.Rider rider = item.rider();
            if (rider != null) {
                ProfileServiceClient.RiderSummaryItem profile = riders.get(rider.riderId());
                if (profile != null) {
                    rider = new DriverStatementItem.Rider(rider.riderId(), profile.firstName(), profile.lastName(), profile.photoUrl());
                }
            }
            return item.with(route == null ? null : new DriverStatementItem.Route(route.routePostId(), route.originLabel(),
                    route.destinationLabel(), route.departureTime()), rider);
        }));
    }

    private static void requireSelfOrOps(String userId, String callerId, UserRole callerRole) {
        if (!userId.equals(callerId) && callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("You can only view your own payments");
        }
    }
}

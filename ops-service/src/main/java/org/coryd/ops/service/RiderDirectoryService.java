package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.util.Names;
import org.coryd.ops.client.PaymentServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.DriverProfileView;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.RiderDirectoryCounts;
import org.coryd.ops.dto.RiderDirectoryItem;
import org.coryd.ops.dto.RiderProfileView;
import org.coryd.ops.dto.RiderReceipt;
import org.coryd.ops.dto.RiderTripItem;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Ops Console - Riders Directory. profile-service keeps one indexed row per rider (with trip-service's numbers and
 * identity's suspensions), so the list is filtered, sorted and paged in its database. Trip history and receipts come
 * from trip-service, paged there, with payment-service's charges and refunds for just that page.
 */
@Service
@RequiredArgsConstructor
public class RiderDirectoryService {

    private final ProfileServiceClient profileServiceClient;
    private final TripServiceClient tripServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final AccountProfileParts accountProfileParts;

    public PagedResponse<RiderDirectoryItem> riders(RiderDirectoryStatus status, String search, RiderDirectorySort sort,
                                                    Sort.Direction direction, int page, int size) {
        ProfileServiceClient.RiderIndexPage result = profileServiceClient.riderIndex(status, search, sort, direction,
                Math.max(0, page), Math.clamp(size, 1, 50));
        return new PagedResponse<>(result.content().stream().map(RiderDirectoryService::row).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages(), result.last());
    }

    public RiderDirectoryCounts counts(String search) {
        return profileServiceClient.riderIndexCounts(search);
    }

    /** The whole profile in one call - see DriverDirectoryService.driver. */
    public RiderProfileView rider(String riderId) {
        ProfileServiceClient.IndexedRider r = profileServiceClient.indexedRider(riderId);
        RiderDirectoryItem row = row(r);
        AccountProfileParts.Parts parts = accountProfileParts.load(riderId);
        return new RiderProfileView(row.rider(), row.status(),
                r.suspendedAt() == null ? null : new DriverProfileView.Suspension(r.suspendedAt(), r.suspensionReason(),
                        DriverDirectoryService.suspendedBy(parts)),
                accountProfileParts.account(parts, row.rider().ref(), row.joinedAt()),
                new RiderProfileView.Activity(row.trips(), row.rating(), row.ratingCount(), row.totalSpent(), row.reliabilityPercent(),
                        row.reportsFiled()),
                AccountProfileParts.email(parts, "rider"),
                accountProfileParts.riderVerification(parts),
                parts.profileField("emergencyContacts"),
                parts.accountField("suspensionHistory"),
                parts.recentActions(),
                parts.accountField("pendingDeletion"),
                AccountProfileParts.namePart(parts, "rider", "firstName"),
                AccountProfileParts.namePart(parts, "rider", "lastName"));
    }

    /** Checks the rider exists first, so an unknown id is a 404 rather than an empty history. */
    public PagedResponse<RiderTripItem> trips(String riderId, String search, int page, int size) {
        profileServiceClient.indexedRider(riderId);
        TripServiceClient.RiderTripPage result = tripServiceClient.riderTrips(riderId, search, Math.max(0, page), Math.clamp(size, 1, 50));
        Map<String, PaymentServiceClient.BookingPayment> payments = paymentServiceClient.bookingPayments(
                result.content().stream().map(TripServiceClient.RiderTrip::bookingId).toList());
        List<RiderTripItem> items = result.content().stream().map(t -> new RiderTripItem(t.bookingId(), TripOpsService.ref("B", t.bookingId()),
                t.routePostId(), t.status(), t.originLabel(), t.destinationLabel(), t.departureTime(), t.seatCount(), t.fareTotal(),
                new RiderTripItem.Driver(t.driverId(), t.driverName(), t.driverRatingAvg(), t.driverRatingCount()),
                payment(payments.get(t.bookingId())))).toList();
        return new PagedResponse<>(items, result.page(), result.size(), result.totalElements(), result.totalPages(), result.last());
    }

    public RiderReceipt receipt(String riderId, String bookingId) {
        ProfileServiceClient.IndexedRider r = profileServiceClient.indexedRider(riderId);
        TripServiceClient.RiderReceipt t = tripServiceClient.riderReceipt(riderId, bookingId);
        PaymentServiceClient.BookingPayment payment = paymentServiceClient.bookingPayments(List.of(bookingId)).get(bookingId);
        return new RiderReceipt(t.bookingId(), TripOpsService.ref("B", t.bookingId()), t.status(), row(r).rider(),
                new RiderReceipt.Route(t.routePostId(), t.originLabel(), t.destinationLabel(), t.pickupLabel(), t.dropoffLabel(), t.departureTime()),
                t.bookedAt(), payment != null && payment.paidAt() != null ? payment.paidAt() : t.paidAt(), t.pickedUpAt(), t.completedAt(),
                new RiderReceipt.Driver(t.driverId(), TripOpsService.ref("D", t.driverId()), t.driverName(), t.vehiclePlate()),
                t.seatCount(), t.farePerSeat(), t.fareTotal(), payment(payment));
    }

    private static RiderTripItem.Payment payment(PaymentServiceClient.BookingPayment p) {
        return p == null ? null : new RiderTripItem.Payment(p.status(), p.method(), p.cardType(), p.cardLast4(), p.reference(), p.paidAt(),
                p.amountPaid(), p.refunded(), p.refundPending(), p.netPaid());
    }

    private static RiderDirectoryItem row(ProfileServiceClient.IndexedRider r) {
        DriverSummary rider = new DriverSummary(r.riderId(), TripOpsService.ref("R", r.riderId()),
                Names.join(r.firstName(), r.lastName()), r.phone(), r.photoUrl());
        return new RiderDirectoryItem(rider, r.trips(), r.reliabilityPercent(), r.ratingAvg(), r.ratingCount(), r.totalSpent(),
                r.reportsFiled(), r.status(), r.joinedAt());
    }
}

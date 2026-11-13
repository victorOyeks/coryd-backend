package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.LiveTripPhase;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.util.Names;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.PaymentServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.CancelBookingRequest;
import org.coryd.ops.dto.CancelTripRequest;
import org.coryd.ops.dto.CancellationCounts;
import org.coryd.ops.dto.CancellationItem;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.LiveTripItem;
import org.coryd.ops.dto.LiveTripsResponse;
import org.coryd.ops.dto.PaymentSummary;
import org.coryd.ops.dto.RefundOutcome;
import org.coryd.ops.dto.RefundState;
import org.coryd.ops.dto.ResolveTripRequest;
import org.coryd.ops.dto.RiderSummary;
import org.coryd.ops.dto.StuckTripItem;
import org.coryd.ops.dto.TimelineEntry;
import org.coryd.ops.dto.TripCountsResponse;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.TripListItem;
import org.coryd.ops.dto.TripLocation;
import org.coryd.ops.dto.VehicleSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Ops Console - Trips table, one trip's detail, the Live Trips monitor, stuck trips, the Cancellations Log, and the
 * trip and booking actions. trip-service supplies the trips and does every action; driver names and photos, vehicles
 * and riders come from profile-service, phones from identity-service, payments and refunds from payment-service - one
 * batched call each per page, never one per row. Every action is signed with the admin's id and name.
 */
@Service
@RequiredArgsConstructor
public class TripOpsService {

    private final TripServiceClient tripServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;
    private final PaymentServiceClient paymentServiceClient;

    // ---- trips table ---------------------------------------------------------------------------------------------

    /** A null status is the "All" tab. */
    public PagedResponse<TripListItem> trips(TripServiceClient.TripQuery query, Pageable pageable) {
        TripServiceClient.TripPage page = tripServiceClient.trips(query, pageable.getPageNumber(), pageable.getPageSize());
        People people = people(page.content().stream().map(TripServiceClient.TripRow::driverId).toList(),
                page.content().stream().map(TripServiceClient.TripRow::vehicleId).toList(), List.of());
        List<TripListItem> items = page.content().stream().map(row -> item(row, people)).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }

    public TripCountsResponse counts(TripServiceClient.TripQuery query) {
        TripServiceClient.TripCounts c = tripServiceClient.counts(query);
        return new TripCountsResponse(c.all(), c.active(), c.completed(), c.cancelled(), c.published(), c.full(), c.inProgress(), c.stuck());
    }

    // ---- one trip ------------------------------------------------------------------------------------------------

    public TripDetailResponse trip(String routePostId) {
        TripServiceClient.TripDetail detail = tripServiceClient.trip(routePostId);
        TripServiceClient.TripRow row = detail.trip();
        List<String> riderIds = detail.passengers().stream().map(TripServiceClient.TripDetail.Passenger::riderId).toList();
        People people = people(List.of(row.driverId()), row.vehicleId() == null ? List.of() : List.of(row.vehicleId()), riderIds);
        List<String> bookingIds = detail.passengers().stream().map(TripServiceClient.TripDetail.Passenger::bookingId).toList();
        Map<String, PaymentServiceClient.BookingPayment> payments = payments(bookingIds);

        List<TripDetailResponse.Passenger> passengers = detail.passengers().stream().map(p -> {
            RiderSummary rider = people.rider(p.riderId());
            PaymentServiceClient.BookingPayment payment = payments.get(p.bookingId());
            CancellationItem cancellation = p.cancellation() == null ? null : cancellationItem(p.cancellation(), people, payments);
            RefundOutcome refund = cancellation != null ? cancellation.refund()
                    : new RefundOutcome(RefundState.NONE, BigDecimal.ZERO, payment == null ? null : payment.refunded(),
                    payment == null ? null : payment.refundPending());
            return new TripDetailResponse.Passenger(p.bookingId(), ref("B", p.bookingId()), p.riderId(), rider.name(), rider.phone(),
                    rider.photoUrl(), p.seatCount(), p.fareTotal(), p.status(), p.pickupLabel(), p.pickupLat(), p.pickupLng(),
                    p.dropoffLabel(), p.confirmedAt(), p.pickedUpAt(), p.completedAt(), p.cancellable(), p.riderFaultFeeNow(),
                    payment(payment), refund, cancellation);
        }).toList();

        List<TimelineEntry> timeline = detail.timeline() == null ? List.of() : detail.timeline().stream()
                .map(t -> timelineEntry(t, row.driverId(), people)).toList();
        CancellationItem cancellation = detail.cancellation() == null ? null : cancellationItem(detail.cancellation(), people, payments);
        TripServiceClient.TripDetail.Actions actions = detail.actions();
        return new TripDetailResponse(item(row, people), detail.originLat(), detail.originLng(),
                detail.destinationLat(), detail.destinationLng(), detail.routeNote(), detail.estimatedDurationMinutes(),
                location(detail.location()), passengers, detail.postedAt(), detail.originalDepartureTime(), detail.timeChangeCount(),
                detail.expectedArrivalAt(), detail.stuck(), detail.stuckSince(), location(detail.lastSeen()), detail.locationAgeSeconds(),
                cancellation, actions == null ? new TripDetailResponse.Actions(false, false)
                : new TripDetailResponse.Actions(actions.cancel(), actions.resolve()), timeline);
    }

    private static TimelineEntry timelineEntry(TripServiceClient.Timeline t, String driverId, People people) {
        String actorName = t.actorName();
        if (actorName == null && t.actorId() != null) {
            actorName = t.actorId().equals(driverId) ? people.driver(driverId).name() : people.rider(t.actorId()).name();
        }
        return new TimelineEntry(t.at(), t.type(), t.actor(), t.actorId(), actorName, t.bookingId(), t.riderId(),
                t.riderId() == null ? null : people.rider(t.riderId()).name(), t.detail());
    }

    // ---- live and stuck ------------------------------------------------------------------------------------------

    public LiveTripsResponse live(String search) {
        List<TripServiceClient.LiveTrip> trips = tripServiceClient.live(search);
        People people = people(trips.stream().map(TripServiceClient.LiveTrip::driverId).toList(), List.of(), List.of());

        List<LiveTripItem> items = trips.stream().map(t -> new LiveTripItem(
                t.routePostId(), ref("T", t.routePostId()), t.phase(), t.originLabel(), t.destinationLabel(),
                t.originLat(), t.originLng(), t.destinationLat(), t.destinationLng(), t.departureTime(), t.startedAt(),
                people.driver(t.driverId()), t.driverRating(), t.driverRatingCount(), t.seatCapacity(), t.seatsBooked(),
                t.elapsedMinutes(), t.estimatedDurationMinutes(), t.etaAt(), t.progress(), location(t.location()),
                t.lastSeen() == null ? null : location(t.lastSeen()), t.locationAgeSeconds(), t.locationStale(), t.overdue(),
                t.stuck())).toList();
        int active = (int) items.stream().filter(i -> i.phase() == LiveTripPhase.ACTIVE).count();
        return new LiveTripsResponse(items.size(), active, items.size() - active, items);
    }

    public PagedResponse<StuckTripItem> stuck(Pageable pageable) {
        TripServiceClient.StuckPage page = tripServiceClient.stuck(pageable.getPageNumber(), pageable.getPageSize());
        People people = people(page.content().stream().map(s -> s.trip().driverId()).toList(),
                page.content().stream().map(s -> s.trip().vehicleId()).toList(), List.of());
        List<StuckTripItem> items = page.content().stream().map(s -> new StuckTripItem(item(s.trip(), people), s.expectedArrivalAt(),
                s.stuckSince(), s.minutesPastExpectedArrival(), s.boarded(), s.awaitingPickup(), s.completed(),
                s.lastSeen() == null ? null : location(s.lastSeen()), s.locationAgeSeconds())).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    public JsonNode cancelTrip(String routePostId, CancelTripRequest request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin, request.reason());
        body.put("fault", request.fault());
        return tripServiceClient.cancelTrip(routePostId, body);
    }

    public JsonNode cancelBooking(String bookingId, CancelBookingRequest request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin, request.reason());
        body.put("fault", request.fault());
        body.put("waiveFee", Boolean.TRUE.equals(request.waiveFee()));
        return tripServiceClient.cancelBooking(bookingId, body);
    }

    public JsonNode resolve(String routePostId, ResolveTripRequest request, CoRydUserDetails admin) {
        Map<String, Object> body = signed(admin, request.reason());
        body.put("outcome", request.outcome());
        if (request.fault() != null) {
            body.put("fault", request.fault());
        }
        if (request.unboardedBookings() != null) {
            body.put("unboardedBookings", request.unboardedBookings());
        }
        return tripServiceClient.resolveTrip(routePostId, body);
    }

    private static Map<String, Object> signed(CoRydUserDetails admin, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason.trim());
        body.put("actorId", admin.getUserId());
        String name = admin.getFullName();
        body.put("actorName", name == null || name.isBlank() ? admin.getUserId() : name);
        return body;
    }

    // ---- cancellations log ---------------------------------------------------------------------------------------

    public PagedResponse<CancellationItem> cancellations(TripServiceClient.CancellationQuery query, Pageable pageable) {
        TripServiceClient.CancellationPage page = tripServiceClient.cancellations(query, pageable.getPageNumber(), pageable.getPageSize());
        People people = people(page.content().stream().map(TripServiceClient.Cancellation::driverId).toList(), List.of(),
                page.content().stream().map(TripServiceClient.Cancellation::riderId).toList());
        List<String> bookingIds = page.content().stream()
                .flatMap(c -> Stream.concat(Stream.ofNullable(c.bookingId()),
                        c.childBookingIds() == null ? Stream.empty() : c.childBookingIds().stream()))
                .toList();
        Map<String, PaymentServiceClient.BookingPayment> payments = payments(bookingIds);
        List<CancellationItem> items = page.content().stream().map(c -> cancellationItem(c, people, payments)).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }

    public CancellationCounts cancellationCounts(TripServiceClient.CancellationQuery query) {
        TripServiceClient.CancellationCounts c = tripServiceClient.cancellationCounts(query);
        return new CancellationCounts(c.all(), c.rider(), c.driver(), c.systemExpiry(), c.system(), c.ops(), c.suspension(),
                c.accountDeleted());
    }

    private CancellationItem cancellationItem(TripServiceClient.Cancellation c, People people,
                                              Map<String, PaymentServiceClient.BookingPayment> payments) {
        RiderSummary rider = c.riderId() == null ? null : people.rider(c.riderId());
        DriverSummary driver = c.driverId() == null ? null : people.driver(c.driverId());
        String actorName = c.actorName();
        if (actorName == null && c.actorId() != null) {
            actorName = c.actorId().equals(c.driverId()) && driver != null ? driver.name()
                    : c.actorId().equals(c.riderId()) && rider != null ? rider.name() : null;
        }
        List<String> seats = c.bookingId() != null ? List.of(c.bookingId())
                : c.childBookingIds() == null ? List.of() : c.childBookingIds();
        return new CancellationItem(c.id(), c.kind(), c.routePostId(), ref("T", c.routePostId()), c.bookingId(), c.originLabel(),
                c.destinationLabel(), c.departureTime(), c.cancelledAt(), c.minutesBeforeDeparture(), c.source(), c.actorId(),
                actorName, c.reason(), c.fault(), rider, driver, c.seatCount(), c.fareTotal(), c.paid(), c.feeAmount(),
                refund(c.paid(), c.refundExpected(), seats, payments), c.bookingsAffected(),
                c.childBookingIds() == null ? List.of() : c.childBookingIds(), c.routeStatusBefore(), c.bookingStatusBefore(),
                c.backfilled());
    }

    /** Sums payment-service's refunds over the seats a cancellation ended and says where that leaves the rider(s). */
    static RefundOutcome refund(boolean paid, BigDecimal expected, Collection<String> bookingIds,
                                Map<String, PaymentServiceClient.BookingPayment> payments) {
        BigDecimal due = expected == null ? BigDecimal.ZERO : expected;
        if (!paid) {
            return new RefundOutcome(RefundState.NOT_PAID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (bookingIds.isEmpty() || !payments.keySet().containsAll(bookingIds)) {
            return new RefundOutcome(due.signum() == 0 ? RefundState.NO_REFUND_DUE : RefundState.UNKNOWN, due, null, null);
        }
        BigDecimal refunded = bookingIds.stream().map(payments::get).map(PaymentServiceClient.BookingPayment::refunded)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = bookingIds.stream().map(payments::get).map(PaymentServiceClient.BookingPayment::refundPending)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        RefundState state;
        if (due.signum() == 0) {
            state = RefundState.NO_REFUND_DUE;
        } else if (refunded.compareTo(due) >= 0) {
            state = RefundState.REFUNDED;
        } else if (pending.signum() > 0) {
            state = RefundState.PENDING;
        } else if (refunded.signum() > 0) {
            state = RefundState.PARTIAL;
        } else {
            state = RefundState.NOT_STARTED;
        }
        return new RefundOutcome(state, due, refunded, pending);
    }

    /** payment-service takes up to 100 bookings a call. */
    private Map<String, PaymentServiceClient.BookingPayment> payments(List<String> bookingIds) {
        List<String> distinct = bookingIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, PaymentServiceClient.BookingPayment> all = new HashMap<>();
        for (int i = 0; i < distinct.size(); i += 100) {
            all.putAll(paymentServiceClient.bookingPayments(distinct.subList(i, Math.min(i + 100, distinct.size()))));
        }
        return all;
    }

    private static PaymentSummary payment(PaymentServiceClient.BookingPayment p) {
        return p == null ? null : new PaymentSummary(p.status(), p.method(), p.cardType(), p.cardLast4(), p.reference(), p.paidAt(),
                p.amountPaid(), p.refunded(), p.refundPending(), p.netPaid());
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private TripListItem item(TripServiceClient.TripRow row, People people) {
        return new TripListItem(row.routePostId(), ref("T", row.routePostId()), row.status(), group(row.status()),
                people.driver(row.driverId()), people.vehicle(row.vehicleId()), row.originLabel(), row.destinationLabel(),
                row.departureTime(), row.seatCapacity(), row.seatsBooked(), row.farePerSeat(), row.totalFare(),
                row.driverRating(), row.driverRatingCount(), row.startedAt(), row.completedAt());
    }

    private People people(List<String> driverIds, List<String> vehicleIds, List<String> riderIds) {
        List<String> drivers = driverIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> riders = riderIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> everyone = new ArrayList<>(drivers);
        riders.stream().filter(r -> !drivers.contains(r)).forEach(everyone::add);
        return new People(profileServiceClient.drivers(drivers), identityServiceClient.contacts(everyone),
                profileServiceClient.vehicles(vehicleIds.stream().filter(Objects::nonNull).distinct().toList()),
                riders.isEmpty() ? Map.of() : profileServiceClient.riders(riders));
    }

    private record People(Map<String, ProfileServiceClient.Driver> drivers,
                          Map<String, IdentityServiceClient.Contact> phones,
                          Map<String, ProfileServiceClient.Vehicle> vehicles,
                          Map<String, ProfileServiceClient.Rider> riders) {

        DriverSummary driver(String driverId) {
            ProfileServiceClient.Driver profile = drivers.get(driverId);
            IdentityServiceClient.Contact contact = phones.get(driverId);
            String name = profile != null ? Names.join(profile.firstName(), profile.lastName())
                    : contact == null ? null : contact.fullName();
            return new DriverSummary(driverId, ref("D", driverId), name,
                    contact == null ? null : contact.phone(), profile == null ? null : profile.photoUrl());
        }

        RiderSummary rider(String riderId) {
            ProfileServiceClient.Rider profile = riders.get(riderId);
            IdentityServiceClient.Contact contact = phones.get(riderId);
            String name = profile != null ? Names.join(profile.firstName(), profile.lastName())
                    : contact == null ? null : contact.fullName();
            return new RiderSummary(riderId, ref("R", riderId), name, contact == null ? null : contact.phone(),
                    profile == null ? null : profile.photoUrl());
        }

        VehicleSummary vehicle(String vehicleId) {
            ProfileServiceClient.Vehicle v = vehicleId == null ? null : vehicles.get(vehicleId);
            return v == null ? null : new VehicleSummary(v.id(), v.make(), v.model(), v.year(), v.color(), v.plateNumber());
        }
    }

    /** Short display reference from the id - "T-2F2F53" - matching how receipts already reference a trip. */
    static String ref(String prefix, String id) {
        if (id == null) {
            return null;
        }
        String compact = id.replace("-", "").toUpperCase(Locale.ROOT);
        return prefix + "-" + compact.substring(0, Math.min(6, compact.length()));
    }

    private static OpsTripStatus group(RouteStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case COMPLETED -> OpsTripStatus.COMPLETED;
            case CANCELLED -> OpsTripStatus.CANCELLED;
            default -> OpsTripStatus.ACTIVE;
        };
    }

    private static TripLocation location(TripServiceClient.Location l) {
        return l == null ? TripLocation.UNAVAILABLE : new TripLocation(l.available(), l.lat(), l.lng(), l.recordedAt());
    }
}

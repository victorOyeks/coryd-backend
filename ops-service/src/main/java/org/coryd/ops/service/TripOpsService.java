package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.LiveTripPhase;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.util.Names;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.LiveTripItem;
import org.coryd.ops.dto.LiveTripsResponse;
import org.coryd.ops.dto.TripCountsResponse;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.TripListItem;
import org.coryd.ops.dto.TripLocation;
import org.coryd.ops.dto.VehicleSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ops Console - Trips table, one trip's detail, and the Live Trips monitor. trip-service supplies the
 * trips; driver names and photos, vehicles and riders come from profile-service, phones from
 * identity-service - one batched call each per page, never one per row.
 */
@Service
@RequiredArgsConstructor
public class TripOpsService {

    private final TripServiceClient tripServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;

    /** A null status is the "All" tab. Newest departure first. */
    public PagedResponse<TripListItem> trips(OpsTripStatus status, String search, Pageable pageable) {
        TripServiceClient.TripPage page = tripServiceClient.trips(status, search, pageable.getPageNumber(), pageable.getPageSize());
        People people = people(page.content().stream().map(TripServiceClient.TripRow::driverId).toList(),
                page.content().stream().map(TripServiceClient.TripRow::vehicleId).toList());
        List<TripListItem> items = page.content().stream().map(row -> item(row, people)).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }

    public TripCountsResponse counts(String search) {
        TripServiceClient.TripCounts counts = tripServiceClient.counts(search);
        return new TripCountsResponse(counts.all(), counts.active(), counts.completed(), counts.cancelled());
    }

    public TripDetailResponse trip(String routePostId) {
        TripServiceClient.TripDetail detail = tripServiceClient.trip(routePostId);
        TripServiceClient.TripRow row = detail.trip();
        List<String> riderIds = detail.passengers().stream().map(TripServiceClient.TripDetail.Passenger::riderId).toList();
        People people = people(List.of(row.driverId()), row.vehicleId() == null ? List.of() : List.of(row.vehicleId()));
        Map<String, ProfileServiceClient.Rider> riders = profileServiceClient.riders(riderIds);
        Map<String, IdentityServiceClient.Contact> riderPhones = identityServiceClient.contacts(riderIds);

        List<TripDetailResponse.Passenger> passengers = detail.passengers().stream().map(p -> {
            ProfileServiceClient.Rider rider = riders.get(p.riderId());
            IdentityServiceClient.Contact contact = riderPhones.get(p.riderId());
            String name = rider != null ? Names.join(rider.firstName(), rider.lastName()) : contact == null ? null : contact.fullName();
            return new TripDetailResponse.Passenger(p.bookingId(), p.riderId(), name, contact == null ? null : contact.phone(),
                    rider == null ? null : rider.photoUrl(), p.seatCount(), p.fareTotal(), p.status(), p.pickupLabel(),
                    p.pickupLat(), p.pickupLng(), p.confirmedAt(), p.pickedUpAt());
        }).toList();

        return new TripDetailResponse(item(row, people), detail.originLat(), detail.originLng(),
                detail.destinationLat(), detail.destinationLng(), detail.routeNote(), detail.estimatedDurationMinutes(),
                location(detail.location()), passengers);
    }

    public LiveTripsResponse live(String search) {
        List<TripServiceClient.LiveTrip> trips = tripServiceClient.live(search);
        People people = people(trips.stream().map(TripServiceClient.LiveTrip::driverId).toList(), List.of());

        List<LiveTripItem> items = trips.stream().map(t -> new LiveTripItem(
                t.routePostId(), ref("T", t.routePostId()), t.phase(), t.originLabel(), t.destinationLabel(),
                t.originLat(), t.originLng(), t.destinationLat(), t.destinationLng(), t.departureTime(), t.startedAt(),
                people.driver(t.driverId()), t.driverRating(), t.driverRatingCount(), t.seatCapacity(), t.seatsBooked(),
                t.elapsedMinutes(), t.estimatedDurationMinutes(), t.etaAt(), t.progress(), location(t.location()))).toList();
        int active = (int) items.stream().filter(i -> i.phase() == LiveTripPhase.ACTIVE).count();
        return new LiveTripsResponse(items.size(), active, items.size() - active, items);
    }

    private TripListItem item(TripServiceClient.TripRow row, People people) {
        return new TripListItem(row.routePostId(), ref("T", row.routePostId()), row.status(), group(row.status()),
                people.driver(row.driverId()), people.vehicle(row.vehicleId()), row.originLabel(), row.destinationLabel(),
                row.departureTime(), row.seatCapacity(), row.seatsBooked(), row.farePerSeat(), row.totalFare(),
                row.driverRating(), row.driverRatingCount(), row.startedAt(), row.completedAt());
    }

    private People people(List<String> driverIds, List<String> vehicleIds) {
        return new People(profileServiceClient.drivers(driverIds), identityServiceClient.contacts(driverIds),
                profileServiceClient.vehicles(vehicleIds));
    }

    private record People(Map<String, ProfileServiceClient.Driver> drivers,
                          Map<String, IdentityServiceClient.Contact> phones,
                          Map<String, ProfileServiceClient.Vehicle> vehicles) {

        DriverSummary driver(String driverId) {
            ProfileServiceClient.Driver profile = drivers.get(driverId);
            IdentityServiceClient.Contact contact = phones.get(driverId);
            String name = profile != null ? Names.join(profile.firstName(), profile.lastName())
                    : contact == null ? null : contact.fullName();
            return new DriverSummary(driverId, ref("D", driverId), name,
                    contact == null ? null : contact.phone(), profile == null ? null : profile.photoUrl());
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

package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.util.Names;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.RiderSummary;
import org.coryd.ops.dto.VehicleSummary;
import org.coryd.ops.dto.safety.PersonSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Names, phones, photos and vehicles for a page of safety and support rows - one batched call per service, never one per
 * row, and blank details rather than a failed page when a lookup is down. Same sources as the trips screens.
 */
@Component
@RequiredArgsConstructor
public class OpsPeople {

    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;

    public Lookup lookup(Collection<String> driverIds, Collection<String> riderIds, Collection<String> vehicleIds) {
        List<String> drivers = driverIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> riders = riderIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> everyone = new ArrayList<>(drivers);
        riders.stream().filter(r -> !drivers.contains(r)).forEach(everyone::add);
        List<String> vehicles = vehicleIds.stream().filter(Objects::nonNull).distinct().toList();
        return new Lookup(drivers.isEmpty() ? Map.of() : profileServiceClient.drivers(drivers),
                everyone.isEmpty() ? Map.of() : identityServiceClient.contacts(everyone),
                vehicles.isEmpty() ? Map.of() : profileServiceClient.vehicles(vehicles),
                riders.isEmpty() ? Map.of() : profileServiceClient.riders(riders));
    }

    public record Lookup(Map<String, ProfileServiceClient.Driver> drivers, Map<String, IdentityServiceClient.Contact> phones,
                         Map<String, ProfileServiceClient.Vehicle> vehicles, Map<String, ProfileServiceClient.Rider> riders) {

        public DriverSummary driver(String driverId) {
            if (driverId == null) {
                return null;
            }
            ProfileServiceClient.Driver profile = drivers.get(driverId);
            IdentityServiceClient.Contact contact = phones.get(driverId);
            String name = profile != null ? Names.join(profile.firstName(), profile.lastName()) : contact == null ? null : contact.fullName();
            return new DriverSummary(driverId, TripOpsService.ref("D", driverId), name, contact == null ? null : contact.phone(),
                    profile == null ? null : profile.photoUrl());
        }

        public RiderSummary rider(String riderId) {
            if (riderId == null) {
                return null;
            }
            ProfileServiceClient.Rider profile = riders.get(riderId);
            IdentityServiceClient.Contact contact = phones.get(riderId);
            String name = profile != null ? Names.join(profile.firstName(), profile.lastName()) : contact == null ? null : contact.fullName();
            return new RiderSummary(riderId, TripOpsService.ref("R", riderId), name, contact == null ? null : contact.phone(),
                    profile == null ? null : profile.photoUrl());
        }

        public VehicleSummary vehicle(String vehicleId) {
            ProfileServiceClient.Vehicle v = vehicleId == null ? null : vehicles.get(vehicleId);
            return v == null ? null : new VehicleSummary(v.id(), v.make(), v.model(), v.year(), v.color(), v.plateNumber());
        }

        /** Someone in a known role - RIDER or DRIVER; null for SYSTEM or no one. */
        public PersonSummary person(String userId, String role) {
            if (userId == null || role == null || "SYSTEM".equals(role)) {
                return null;
            }
            if ("DRIVER".equals(role)) {
                DriverSummary d = driver(userId);
                return new PersonSummary(d.id(), d.ref(), "DRIVER", d.name(), d.phone(), d.photoUrl());
            }
            RiderSummary r = rider(userId);
            return new PersonSummary(r.id(), r.ref(), role, r.name(), r.phone(), r.photoUrl());
        }
    }
}

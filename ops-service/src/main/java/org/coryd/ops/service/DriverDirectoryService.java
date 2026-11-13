package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DriverDirectorySort;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.util.Names;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.DriverDirectoryCounts;
import org.coryd.ops.dto.DriverDirectoryItem;
import org.coryd.ops.dto.DriverProfileView;
import org.coryd.ops.dto.DriverSummary;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Ops Console - Drivers Directory. profile-service keeps one indexed row per driver (its own records plus trip,
 * payment and identity numbers, kept current by events), so filtering, sorting and paging all happen in its database;
 * this only shapes the result for the console.
 */
@Service
@RequiredArgsConstructor
public class DriverDirectoryService {

    private final ProfileServiceClient profileServiceClient;
    private final AccountProfileParts accountProfileParts;

    /** status null is "All". Newest sign-up first unless sorted. */
    public PagedResponse<DriverDirectoryItem> drivers(DriverDirectoryStatus status, String search, DriverDirectorySort sort,
                                                      Sort.Direction direction, int page, int size) {
        ProfileServiceClient.IndexPage result = profileServiceClient.driverIndex(status, search, sort, direction,
                Math.max(0, page), Math.clamp(size, 1, 50));
        return new PagedResponse<>(result.content().stream().map(DriverDirectoryService::row).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages(), result.last());
    }

    public DriverDirectoryCounts counts(String search) {
        return profileServiceClient.driverIndexCounts(search);
    }

    /**
     * The whole profile in one call: the row's numbers and reports filed (profile-service's index), the account, devices,
     * suspension history and recent account history (identity-service), and email, verification, vehicles and emergency
     * contacts (profile-service).
     */
    public DriverProfileView driver(String driverId) {
        ProfileServiceClient.IndexedDriver d = profileServiceClient.indexedDriver(driverId);
        DriverDirectoryItem row = row(d);
        AccountProfileParts.Parts parts = accountProfileParts.load(driverId);
        return new DriverProfileView(row.driver(), row.status(), row.verificationStatus(),
                d.suspendedAt() == null ? null : new DriverProfileView.Suspension(d.suspendedAt(), d.suspensionReason(),
                        suspendedBy(parts)),
                row.vehicle(),
                new DriverProfileView.Performance(row.trips(), row.rating(), row.ratingCount(), row.earnings(), row.grossEarnings(),
                        row.reliabilityPercent(), d.reportsFiled()),
                accountProfileParts.account(parts, row.driver().ref(), row.joinedAt()),
                AccountProfileParts.email(parts, "driver"),
                accountProfileParts.driverVerification(parts),
                parts.profileField("vehicles"),
                parts.profileField("emergencyContacts"),
                parts.accountField("suspensionHistory"),
                parts.recentActions(),
                parts.accountField("pendingDeletion"),
                AccountProfileParts.namePart(parts, "driver", "firstName"),
                AccountProfileParts.namePart(parts, "driver", "lastName"));
    }

    static com.fasterxml.jackson.databind.JsonNode suspendedBy(AccountProfileParts.Parts parts) {
        com.fasterxml.jackson.databind.JsonNode suspension = parts.accountField("suspension");
        return suspension == null || suspension.get("suspendedBy") == null || suspension.get("suspendedBy").isNull()
                ? null : suspension.get("suspendedBy");
    }

    private static DriverDirectoryItem row(ProfileServiceClient.IndexedDriver d) {
        DriverSummary driver = new DriverSummary(d.driverId(), TripOpsService.ref("D", d.driverId()),
                Names.join(d.firstName(), d.lastName()), d.phone(), d.photoUrl());
        boolean hasVehicle = d.vehicleMake() != null || d.vehicleModel() != null || d.plateNumber() != null;
        return new DriverDirectoryItem(driver,
                hasVehicle ? new DriverDirectoryItem.Vehicle(d.vehicleMake(), d.vehicleModel(), d.vehicleYear(), d.vehicleColor(), d.plateNumber()) : null,
                d.trips(), d.ratingAvg(), d.ratingCount(), d.earningsNet(), d.earningsGross(), d.reliabilityPercent(),
                d.status(), d.verificationStatus(), d.joinedAt(), d.lastTripAt());
    }
}

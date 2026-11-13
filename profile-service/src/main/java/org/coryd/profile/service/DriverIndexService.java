package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.DriverDirectorySort;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.DriverIndexCounts;
import org.coryd.profile.dto.DriverIndexView;
import org.coryd.profile.dto.VerificationQueueItem;
import org.coryd.profile.entity.DriverIndex;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverIndexRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * driver_index: one row per driver for the Ops Console. refresh() rebuilds a row's own fields from the driver records
 * here; trip, payment and identity fields are written as their events arrive and left alone otherwise. The Drivers
 * Directory reads it with one indexed query - filter, sort and page all in the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverIndexService {

    private final DriverIndexRepository driverIndexRepository;
    private final VerificationAggregationService verificationAggregationService;
    private final IdentityServiceClient identityServiceClient;

    /** Rebuilds the row from the driver records; removes it when the driver profile is gone. */
    @Transactional
    public void refresh(String driverId) {
        VerificationAggregationService.IndexFacts facts = verificationAggregationService.indexFacts(driverId);
        if (facts == null) {
            driverIndexRepository.findById(driverId).ifPresent(driverIndexRepository::delete);
            return;
        }
        DriverIndex row = driverIndexRepository.findById(driverId).orElseGet(() -> DriverIndex.builder().driverId(driverId).build());
        apply(row, facts);
        if (row.getPhone() == null || row.getJoinedAt() == null) {
            identityServiceClient.contacts(List.of(driverId)).stream().findFirst().ifPresent(c -> applyContact(row, c));
        }
        save(row);
    }

    /** refresh() for many drivers, with one identity call for all of them - the backfill and nightly rebuild. */
    @Transactional
    public int refreshAll(Collection<String> driverIds) {
        Map<String, IdentityServiceClient.Contact> contacts = identityServiceClient.contacts(driverIds).stream()
                .collect(Collectors.toMap(IdentityServiceClient.Contact::userId, Function.identity(), (a, b) -> a));
        int done = 0;
        for (String driverId : driverIds) {
            VerificationAggregationService.IndexFacts facts = verificationAggregationService.indexFacts(driverId);
            if (facts == null) {
                driverIndexRepository.findById(driverId).ifPresent(driverIndexRepository::delete);
                continue;
            }
            DriverIndex row = driverIndexRepository.findById(driverId).orElseGet(() -> DriverIndex.builder().driverId(driverId).build());
            apply(row, facts);
            IdentityServiceClient.Contact contact = contacts.get(driverId);
            if (contact != null) {
                applyContact(row, contact);
            }
            save(row);
            done++;
        }
        return done;
    }

    /** trip-service's numbers - ignored when older than what the row already has, or for someone who isn't a driver. */
    @Transactional
    public void applyStats(DriverStatsChangedEvent event) {
        DriverIndex row = rowFor(event.getDriverId());
        if (row == null || isOlder(event.getComputedAt(), row.getStatsAt())) {
            return;
        }
        row.setTrips(event.getTrips());
        row.setRatingAvg(event.getRatingAvg());
        row.setRatingCount(event.getRatingCount());
        row.setReliabilityPercent(event.getReliabilityPercent());
        row.setReportsFiled(event.getReportsFiled());
        if (event.getLastTripAt() != null || event.getTrips() == 0) {
            // No lastTripAt with trips on record is a sender that predates the field - keep what the row has.
            row.setLastTripAt(event.getLastTripAt());
        }
        row.setStatsAt(event.getComputedAt());
        save(row);
    }

    @Transactional
    public void applyEarnings(DriverEarningsChangedEvent event) {
        DriverIndex row = rowFor(event.getDriverId());
        if (row == null || isOlder(event.getComputedAt(), row.getEarningsAt())) {
            return;
        }
        row.setEarningsNet(event.getLifetimeNet());
        row.setEarningsGross(event.getLifetimeGross());
        row.setEarningsAt(event.getComputedAt());
        save(row);
    }

    /** suspendedAt null means reinstated. Riders have no row - nothing to do. */
    @Transactional
    public void applySuspension(String userId, Instant suspendedAt, String reason) {
        driverIndexRepository.findById(userId).ifPresent(row -> {
            row.setSuspendedAt(suspendedAt);
            row.setSuspensionReason(suspendedAt == null ? null : reason);
            save(row);
        });
    }

    // ---- reading ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<DriverIndexView> directory(DriverDirectoryStatus status, String search, DriverDirectorySort sort,
                                                    Sort.Direction direction, int page, int size) {
        Specification<DriverIndex> spec = DriverIndexSpecs.directorySearch(search).and(DriverIndexSpecs.status(status))
                .and(DriverIndexSpecs.directorySort(sort, direction));
        Page<DriverIndex> rows = driverIndexRepository.findAll(spec, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        return PagedResponse.from(rows.map(DriverIndexService::view));
    }

    @Transactional(readOnly = true)
    public DriverIndexCounts counts(String search) {
        Specification<DriverIndex> base = DriverIndexSpecs.directorySearch(search);
        return new DriverIndexCounts(driverIndexRepository.count(base),
                driverIndexRepository.count(base.and(DriverIndexSpecs.status(DriverDirectoryStatus.VERIFIED))),
                driverIndexRepository.count(base.and(DriverIndexSpecs.status(DriverDirectoryStatus.PENDING))),
                driverIndexRepository.count(base.and(DriverIndexSpecs.status(DriverDirectoryStatus.RESUBMITTED))),
                driverIndexRepository.count(base.and(DriverIndexSpecs.status(DriverDirectoryStatus.SUSPENDED))),
                driverIndexRepository.count(base.and(DriverIndexSpecs.lastTripSince(Instant.now().minus(RiderIndexService.ACTIVE_WINDOW)))));
    }

    @Transactional(readOnly = true)
    public DriverIndexView driver(String driverId) {
        return driverIndexRepository.findById(driverId).map(DriverIndexService::view)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
    }

    // ---- building a row --------------------------------------------------------------------------

    private void apply(DriverIndex row, VerificationAggregationService.IndexFacts facts) {
        DriverProfile p = facts.profile();
        row.setRefKey(row.getDriverId().replace("-", "").toLowerCase(Locale.ROOT));
        row.setFirstName(p.getFirstName());
        row.setLastName(p.getLastName());
        String name = (Objects.toString(p.getFirstName(), "") + " " + Objects.toString(p.getLastName(), "")).trim();
        row.setNameKey(name.isEmpty() ? null : name.toLowerCase(Locale.ROOT));
        row.setPhotoUrl(p.getPhotoUrl());
        if (row.getJoinedAt() == null) {
            row.setJoinedAt(p.getCreatedAt());
        }
        row.setOverallStatus(facts.overallStatus());
        List<VerificationQueueItem.Entry> entries = facts.entries();
        row.setResubmitted(entries.stream().anyMatch(e -> e.awaitingOps() && e.resubmitted()));
        Vehicle v = facts.vehicle();
        row.setVehicleMake(v == null ? null : v.getMake());
        row.setVehicleModel(v == null ? null : v.getModel());
        row.setVehicleYear(v == null ? null : v.getYear());
        row.setVehicleColor(v == null ? null : v.getColor());
        row.setPlateNumber(v == null ? null : v.getPlateNumber());
        row.setPlateKey(v == null || v.getPlateNumber() == null ? null : v.getPlateNumber().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT));
        String vehicleKey = v == null ? "" : (Objects.toString(v.getMake(), "") + " " + Objects.toString(v.getModel(), "")).trim();
        row.setVehicleKey(vehicleKey.isEmpty() ? null : vehicleKey.toLowerCase(Locale.ROOT));
        row.setInReview(entries.stream().anyMatch(e -> e.status() == VerificationStatus.IN_REVIEW));
        VerificationQueueItem.Entry licence = entries.get(0);
        row.setLicenceCheckStartedAt(licence.awaitingProvider() ? licence.submittedAt() : null);
        row.setQueueTab(facts.queueTab());
        row.setWaitingSince(facts.waitingSince());
        row.setSubmittedAt(entries.stream().map(VerificationQueueItem.Entry::submittedAt).filter(Objects::nonNull)
                .min(java.util.Comparator.naturalOrder()).orElse(null));
    }

    private static void applyContact(DriverIndex row, IdentityServiceClient.Contact contact) {
        if (contact.phone() != null) {
            row.setPhone(contact.phone());
            row.setPhoneDigits(contact.phone().replaceAll("[^0-9]", ""));
        }
        if (contact.joinedAt() != null) {
            row.setJoinedAt(contact.joinedAt());
        }
        row.setSuspendedAt(contact.suspendedAt());
        row.setSuspensionReason(contact.suspendedAt() == null ? null : contact.suspensionReason());
    }

    private void save(DriverIndex row) {
        row.setDirectoryStatus(statusOf(row));
        row.setRefreshedAt(Instant.now());
        driverIndexRepository.save(row);
    }

    /** Each driver has exactly one: suspended, then verified, then resubmitted, then pending. */
    static DriverDirectoryStatus statusOf(DriverIndex row) {
        if (row.getSuspendedAt() != null) {
            return DriverDirectoryStatus.SUSPENDED;
        }
        if (row.getOverallStatus() == VerificationStatus.APPROVED) {
            return DriverDirectoryStatus.VERIFIED;
        }
        return row.isResubmitted() ? DriverDirectoryStatus.RESUBMITTED : DriverDirectoryStatus.PENDING;
    }

    /** The row to put another service's numbers on - built first if the driver exists but isn't indexed yet. */
    private DriverIndex rowFor(String driverId) {
        if (driverId == null) {
            return null;
        }
        DriverIndex row = driverIndexRepository.findById(driverId).orElse(null);
        if (row == null && verificationAggregationService.indexFacts(driverId) != null) {
            refresh(driverId);
            row = driverIndexRepository.findById(driverId).orElse(null);
        }
        return row;
    }

    private static boolean isOlder(Instant incoming, Instant current) {
        return incoming != null && current != null && incoming.isBefore(current);
    }

    private static DriverIndexView view(DriverIndex r) {
        return new DriverIndexView(r.getDriverId(), r.getFirstName(), r.getLastName(), r.getPhone(), r.getPhotoUrl(), r.getJoinedAt(),
                r.getDirectoryStatus(), r.getOverallStatus(), r.getSuspendedAt(), r.getSuspensionReason(),
                r.getVehicleMake(), r.getVehicleModel(), r.getVehicleYear(), r.getVehicleColor(), r.getPlateNumber(),
                r.getTrips(), r.getRatingAvg(), r.getRatingCount(), r.getReliabilityPercent(), r.getReportsFiled(),
                r.getEarningsNet(), r.getEarningsGross(), r.getLastTripAt());
    }
}

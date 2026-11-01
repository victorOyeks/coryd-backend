package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.events.RiderStatsChangedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.RiderIndexCounts;
import org.coryd.profile.dto.RiderIndexView;
import org.coryd.profile.entity.RiderIndex;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.repository.RiderProfileRepository;
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
 * rider_index: one row per rider for the Ops Console's Riders Directory. refresh() rebuilds a row from the rider's profile
 * and identity; trip numbers and suspensions are written as their events arrive. Only accounts holding the rider role
 * are listed - a driver who saved an emergency contact has a rider profile but isn't a rider.
 */
@Service
@RequiredArgsConstructor
public class RiderIndexService {

    private final RiderIndexRepository riderIndexRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final IdentityServiceClient identityServiceClient;

    @Transactional
    public void refresh(String riderId) {
        RiderProfile profile = riderProfileRepository.findByUserId(riderId).orElse(null);
        if (profile == null) {
            riderIndexRepository.findById(riderId).ifPresent(riderIndexRepository::delete);
            return;
        }
        RiderIndex row = riderIndexRepository.findById(riderId).orElse(null);
        IdentityServiceClient.Contact contact = null;
        if (row == null || row.getPhone() == null || row.getJoinedAt() == null) {
            contact = identityServiceClient.contacts(List.of(riderId)).stream().findFirst().orElse(null);
        }
        build(row, profile, contact);
    }

    /** refresh() for many riders, with one identity call - the backfill and nightly rebuild. */
    @Transactional
    public int refreshAll(Collection<String> riderIds) {
        Map<String, RiderProfile> profiles = riderProfileRepository.findByUserIdIn(List.copyOf(riderIds)).stream()
                .collect(Collectors.toMap(RiderProfile::getUserId, Function.identity(), (a, b) -> a));
        Map<String, IdentityServiceClient.Contact> contacts = identityServiceClient.contacts(riderIds).stream()
                .collect(Collectors.toMap(IdentityServiceClient.Contact::userId, Function.identity(), (a, b) -> a));
        int done = 0;
        for (String riderId : riderIds) {
            RiderProfile profile = profiles.get(riderId);
            if (profile == null) {
                riderIndexRepository.findById(riderId).ifPresent(riderIndexRepository::delete);
                continue;
            }
            if (build(riderIndexRepository.findById(riderId).orElse(null), profile, contacts.get(riderId))) {
                done++;
            }
        }
        return done;
    }

    /** trip-service's numbers - ignored when older than what the row has, or for someone with no rider row. */
    @Transactional
    public void applyStats(RiderStatsChangedEvent event) {
        RiderIndex row = rowFor(event.getRiderId());
        if (row == null || isOlder(event.getComputedAt(), row.getStatsAt())) {
            return;
        }
        row.setTrips(event.getTrips());
        row.setRatingAvg(event.getRatingAvg());
        row.setRatingCount(event.getRatingCount());
        row.setReliabilityPercent(event.getReliabilityPercent());
        row.setReportsFiled(event.getReportsFiled());
        row.setTotalSpent(event.getTotalSpent());
        row.setStatsAt(event.getComputedAt());
        save(row);
    }

    /** suspendedAt null means reinstated. */
    @Transactional
    public void applySuspension(String userId, Instant suspendedAt, String reason) {
        riderIndexRepository.findById(userId).ifPresent(row -> {
            row.setSuspendedAt(suspendedAt);
            row.setSuspensionReason(suspendedAt == null ? null : reason);
            save(row);
        });
    }

    // ---- reading ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<RiderIndexView> directory(RiderDirectoryStatus status, String search, RiderDirectorySort sort,
                                                   Sort.Direction direction, int page, int size) {
        Specification<RiderIndex> spec = RiderIndexSpecs.search(search).and(RiderIndexSpecs.status(status))
                .and(RiderIndexSpecs.sort(sort, direction));
        Page<RiderIndex> rows = riderIndexRepository.findAll(spec, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        return PagedResponse.from(rows.map(RiderIndexService::view));
    }

    @Transactional(readOnly = true)
    public RiderIndexCounts counts(String search) {
        Specification<RiderIndex> base = RiderIndexSpecs.search(search);
        return new RiderIndexCounts(riderIndexRepository.count(base),
                riderIndexRepository.count(base.and(RiderIndexSpecs.status(RiderDirectoryStatus.ACTIVE))),
                riderIndexRepository.count(base.and(RiderIndexSpecs.status(RiderDirectoryStatus.SUSPENDED))));
    }

    @Transactional(readOnly = true)
    public RiderIndexView rider(String riderId) {
        return riderIndexRepository.findById(riderId).map(RiderIndexService::view)
                .orElseThrow(() -> new ResourceNotFoundException("Rider not found"));
    }

    // ---- building a row --------------------------------------------------------------------------

    /** false when the account isn't a rider - any row for it is removed. contact may be null (identity unreachable). */
    private boolean build(RiderIndex existing, RiderProfile profile, IdentityServiceClient.Contact contact) {
        String riderId = profile.getUserId();
        if (contact != null && !contact.isRider()) {
            if (existing != null) {
                riderIndexRepository.delete(existing);
            }
            return false;
        }
        RiderIndex row = existing != null ? existing : RiderIndex.builder().riderId(riderId).build();
        row.setRefKey(riderId.replace("-", "").toLowerCase(Locale.ROOT));
        row.setFirstName(profile.getFirstName());
        row.setLastName(profile.getLastName());
        String name = (Objects.toString(profile.getFirstName(), "") + " " + Objects.toString(profile.getLastName(), "")).trim();
        row.setNameKey(name.isEmpty() ? null : name.toLowerCase(Locale.ROOT));
        row.setPhotoUrl(profile.getPhotoUrl());
        if (row.getJoinedAt() == null) {
            row.setJoinedAt(profile.getCreatedAt());
        }
        if (contact != null) {
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
        save(row);
        return true;
    }

    private void save(RiderIndex row) {
        row.setDirectoryStatus(row.getSuspendedAt() != null ? RiderDirectoryStatus.SUSPENDED : RiderDirectoryStatus.ACTIVE);
        row.setRefreshedAt(Instant.now());
        riderIndexRepository.save(row);
    }

    /** The row to put trip numbers on - built first if the rider has a profile but isn't indexed yet. */
    private RiderIndex rowFor(String riderId) {
        if (riderId == null) {
            return null;
        }
        RiderIndex row = riderIndexRepository.findById(riderId).orElse(null);
        if (row == null && riderProfileRepository.findByUserId(riderId).isPresent()) {
            refresh(riderId);
            row = riderIndexRepository.findById(riderId).orElse(null);
        }
        return row;
    }

    private static boolean isOlder(Instant incoming, Instant current) {
        return incoming != null && current != null && incoming.isBefore(current);
    }

    private static RiderIndexView view(RiderIndex r) {
        return new RiderIndexView(r.getRiderId(), r.getFirstName(), r.getLastName(), r.getPhone(), r.getPhotoUrl(), r.getJoinedAt(),
                r.getDirectoryStatus(), r.getSuspendedAt(), r.getSuspensionReason(),
                r.getTrips(), r.getRatingAvg(), r.getRatingCount(), r.getReliabilityPercent(), r.getReportsFiled(), r.getTotalSpent());
    }
}

package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.enums.RiderIdentityStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
 * and identity (the account, and their identity (NIN) check); trip numbers and suspensions are written as their events arrive. Only accounts holding the rider role
 * are listed - a driver who saved an emergency contact has a rider profile but isn't a rider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderIndexService {

    private final RiderIndexRepository riderIndexRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final IdentityServiceClient identityServiceClient;
    private final RiderIdentityStatuses identityStatuses;
    private final PlatformTransactionManager transactionManager;

    /** The "active in the last 7 days" count: a completed trip within this long of now. */
    static final Duration ACTIVE_WINDOW = Duration.ofDays(7);

    /**
     * Rebuilds one rider's row. Safe to run for the same rider from two places at once (the backfill and a profile save,
     * say): the row is read locked, and a missing one is inserted with ON CONFLICT DO NOTHING before it's filled in, so the
     * second writer waits and then updates instead of hitting the primary key.
     */
    @Transactional
    public void refresh(String riderId) {
        RiderProfile profile = riderProfileRepository.findByUserId(riderId).orElse(null);
        if (profile == null) {
            riderIndexRepository.findForUpdate(riderId).ifPresent(riderIndexRepository::delete);
            return;
        }
        RiderIndex row = riderIndexRepository.findForUpdate(riderId).orElse(null);
        IdentityServiceClient.Contact contact = null;
        if (row == null || row.getPhone() == null || row.getJoinedAt() == null) {
            contact = identityServiceClient.contacts(List.of(riderId)).stream().findFirst().orElse(null);
        }
        build(row, profile, contact, identityStatuses.of(riderId));
    }

    /**
     * refresh() for many riders, with one identity call - the backfill and nightly rebuild. Each rider is written in its
     * own transaction: one that fails is logged and counted, and the rest are still written.
     */
    public IndexRefresh refreshAll(Collection<String> riderIds) {
        Map<String, RiderProfile> profiles = riderProfileRepository.findByUserIdIn(List.copyOf(riderIds)).stream()
                .collect(Collectors.toMap(RiderProfile::getUserId, Function.identity(), (a, b) -> a));
        Map<String, IdentityServiceClient.Contact> contacts = identityServiceClient.contacts(riderIds).stream()
                .collect(Collectors.toMap(IdentityServiceClient.Contact::userId, Function.identity(), (a, b) -> a));
        Map<String, RiderIdentityStatus> identity = identityStatuses.of(profiles.keySet());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int done = 0;
        int failed = 0;
        for (String riderId : riderIds) {
            try {
                Boolean indexed = tx.execute(status -> {
                    RiderProfile profile = profiles.get(riderId);
                    if (profile == null) {
                        riderIndexRepository.findForUpdate(riderId).ifPresent(riderIndexRepository::delete);
                        return false;
                    }
                    return build(riderIndexRepository.findForUpdate(riderId).orElse(null), profile, contacts.get(riderId),
                            identity.get(riderId));
                });
                if (Boolean.TRUE.equals(indexed)) {
                    done++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Rider index: could not rebuild the row for {} - carrying on with the rest: {}", riderId, e.toString());
            }
        }
        return new IndexRefresh(done, failed);
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
        if (event.getLastTripAt() != null || event.getTrips() == 0) {
            // No lastTripAt with trips on record is a sender that predates the field - keep what the row has.
            row.setLastTripAt(event.getLastTripAt());
        }
        row.setStatsAt(event.getComputedAt());
        save(row);
    }

    /** suspendedAt null means reinstated. */
    @Transactional
    public void applySuspension(String userId, Instant suspendedAt, String reason) {
        riderIndexRepository.findForUpdate(userId).ifPresent(row -> {
            row.setSuspendedAt(suspendedAt);
            row.setSuspensionReason(suspendedAt == null ? null : reason);
            save(row);
        });
    }

    // ---- reading ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<RiderIndexView> directory(RiderDirectoryStatus status, RiderIdentityStatus identity, String search,
                                                   RiderDirectorySort sort, Sort.Direction direction, int page, int size) {
        Specification<RiderIndex> spec = RiderIndexSpecs.search(search).and(RiderIndexSpecs.status(status))
                .and(RiderIndexSpecs.identity(identity)).and(RiderIndexSpecs.sort(sort, direction));
        Page<RiderIndex> rows = riderIndexRepository.findAll(spec, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        return PagedResponse.from(rows.map(RiderIndexService::view));
    }

    @Transactional(readOnly = true)
    public RiderIndexCounts counts(String search) {
        Specification<RiderIndex> base = RiderIndexSpecs.search(search);
        return new RiderIndexCounts(riderIndexRepository.count(base),
                riderIndexRepository.count(base.and(RiderIndexSpecs.status(RiderDirectoryStatus.ACTIVE))),
                riderIndexRepository.count(base.and(RiderIndexSpecs.status(RiderDirectoryStatus.SUSPENDED))),
                riderIndexRepository.count(base.and(RiderIndexSpecs.identity(RiderIdentityStatus.APPROVED))),
                riderIndexRepository.count(base.and(RiderIndexSpecs.lastTripSince(Instant.now().minus(ACTIVE_WINDOW)))));
    }

    @Transactional(readOnly = true)
    public RiderIndexView rider(String riderId) {
        return riderIndexRepository.findById(riderId).map(RiderIndexService::view)
                .orElseThrow(() -> new ResourceNotFoundException("Rider not found"));
    }

    // ---- building a row --------------------------------------------------------------------------

    /** false when the account isn't a rider - any row for it is removed. contact may be null (identity unreachable). */
    private boolean build(RiderIndex existing, RiderProfile profile, IdentityServiceClient.Contact contact,
                          RiderIdentityStatus identityStatus) {
        String riderId = profile.getUserId();
        if (contact != null && !contact.isRider()) {
            if (existing != null) {
                riderIndexRepository.delete(existing);
            }
            return false;
        }
        String refKey = riderId.replace("-", "").toLowerCase(Locale.ROOT);
        RiderIndex row = existing != null ? existing : newRow(riderId, refKey);
        row.setRefKey(refKey);
        row.setFirstName(profile.getFirstName());
        row.setLastName(profile.getLastName());
        String name = (Objects.toString(profile.getFirstName(), "") + " " + Objects.toString(profile.getLastName(), "")).trim();
        row.setNameKey(name.isEmpty() ? null : name.toLowerCase(Locale.ROOT));
        row.setPhotoUrl(profile.getPhotoUrl());
        row.setIdentityStatus(identityStatus == null ? RiderIdentityStatus.NOT_STARTED : identityStatus);
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

    /** A row for a rider who has none: inserted bare (or found, if another writer just inserted it), then locked. */
    private RiderIndex newRow(String riderId, String refKey) {
        riderIndexRepository.insertIfAbsent(riderId, refKey);
        return riderIndexRepository.findForUpdate(riderId)
                .orElseThrow(() -> new IllegalStateException("rider_index row for " + riderId + " vanished after insert"));
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
        RiderIndex row = riderIndexRepository.findForUpdate(riderId).orElse(null);
        if (row == null && riderProfileRepository.findByUserId(riderId).isPresent()) {
            refresh(riderId);
            row = riderIndexRepository.findForUpdate(riderId).orElse(null);
        }
        return row;
    }

    private static boolean isOlder(Instant incoming, Instant current) {
        return incoming != null && current != null && incoming.isBefore(current);
    }

    private static RiderIndexView view(RiderIndex r) {
        return new RiderIndexView(r.getRiderId(), r.getFirstName(), r.getLastName(), r.getPhone(), r.getPhotoUrl(), r.getJoinedAt(),
                r.getDirectoryStatus(), r.getSuspendedAt(), r.getSuspensionReason(),
                r.getTrips(), r.getRatingAvg(), r.getRatingCount(), r.getReliabilityPercent(), r.getReportsFiled(), r.getTotalSpent(),
                r.getIdentityStatus(), r.getLastTripAt());
    }
}

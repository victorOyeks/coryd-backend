package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationQueueSort;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.IdentityReviewRow;
import org.coryd.profile.dto.PartialNameMatchItem;
import org.coryd.profile.dto.VerificationQueueItem;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Everyone who has submitted something and is waiting on a verification officer - the morning digest's rows
 * (notification-service, docs/ops-console/onboarding-reminders.md): drivers in the verification queue, identity checks in
 * review, and partial name matches nobody has reviewed. Read through the same queries the console screens use, so the
 * digest and the screens always agree. At most {@link #MAX_PER_KIND} of each kind (oldest first); the counts are exact.
 */
@Service
@RequiredArgsConstructor
public class VerificationWaitingService {

    public static final int MAX_PER_KIND = 500;
    private static final int PAGE = 50;

    public enum Kind { DRIVER_DOCUMENTS, IDENTITY_CHECK, PARTIAL_NAME_MATCH }

    /**
     * One person waiting. what: what the officer is to look at ("Insurance certificate, Vehicle registration (re-uploaded)").
     * waitingSince: when the oldest still-waiting part arrived - the "how long" counts from it.
     */
    public record Item(Kind kind, String userId, String role, String ref, String name, String what, Instant submittedAt,
                       Instant waitingSince) {
    }

    public record Waiting(long drivers, long identityChecks, long partialNameMatches, List<Item> items) {
    }

    private final VerificationAggregationService aggregation;
    private final IdentityReviewService identityReview;
    private final IdentityVerificationService identityVerification;

    @Transactional(readOnly = true)
    public Waiting waiting() {
        List<Item> items = new ArrayList<>();
        long drivers = collect(p -> aggregation.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(p, PAGE),
                VerificationQueueSort.WAITING_SINCE, Sort.Direction.ASC), VerificationWaitingService::driver, items);
        long identities = collect(p -> identityReview.list(UserRole.OPS_ADMIN, IdentityReviewTab.IN_REVIEW, null, null,
                IdentityReviewSort.SUBMITTED_AT, Sort.Direction.ASC, p, PAGE), VerificationWaitingService::identity, items);
        long partial = collect(p -> identityVerification.partialNameMatches(UserRole.OPS_ADMIN, PageRequest.of(p, PAGE)),
                VerificationWaitingService::partial, items);
        return new Waiting(drivers, identities, partial, items);
    }

    private static <T> long collect(IntFunction<PagedResponse<T>> pages, java.util.function.Function<T, Item> toItem, List<Item> into) {
        long total = 0;
        int taken = 0;
        for (int page = 0; taken < MAX_PER_KIND; page++) {
            PagedResponse<T> result = pages.apply(page);
            total = result.totalElements();
            for (T row : result.content()) {
                if (taken++ < MAX_PER_KIND) {
                    into.add(toItem.apply(row));
                }
            }
            if (result.last() || result.content().isEmpty()) {
                break;
            }
        }
        return total;
    }

    static Item driver(VerificationQueueItem row) {
        String what = row.items().stream().filter(VerificationQueueItem.Entry::awaitingOps)
                .map(e -> e.label() + (e.awaitingProvider() ? " (still with Smile ID)" : e.resubmitted() ? " (re-uploaded)" : ""))
                .collect(Collectors.joining(", "));
        return new Item(Kind.DRIVER_DOCUMENTS, row.driverId(), "DRIVER", ref("DRIVER", row.driverId()), name(row.firstName(), row.lastName()),
                what.isEmpty() ? "Driver documents" : what, row.submittedAt(), row.waitingSince() == null ? row.submittedAt() : row.waitingSince());
    }

    static Item identity(IdentityReviewRow row) {
        String role = row.driver() && !row.rider() ? "DRIVER" : "RIDER";
        String what = "Identity (NIN) check" + (row.latestOutcome() == null ? "" : " - " + outcome(row.latestOutcome()));
        return new Item(Kind.IDENTITY_CHECK, row.userId(), role, ref(role, row.userId()), name(row.firstName(), row.lastName()), what,
                row.submittedAt(), row.submittedAt());
    }

    static Item partial(PartialNameMatchItem row) {
        String role = row.checkType() == VerificationCheckType.DRIVERS_LICENCE || row.checkType() == VerificationCheckType.NIN_LOOKUP
                ? "DRIVER" : "RIDER";
        String on = switch (row.checkType()) {
            case NIN_FACE -> "NIN check";
            case DRIVERS_LICENCE -> "driver's licence";
            case NIN_LOOKUP -> "NIN against licence";
            case VEHICLE_PLATE -> "vehicle plate";
        };
        return new Item(Kind.PARTIAL_NAME_MATCH, row.userId(), role, ref(role, row.userId()), name(row.firstName(), row.lastName()),
                "Partial name match on " + on + " - approved, needs a look", row.approvedAt(), row.approvedAt());
    }

    /** "name mismatch" - Smile ID's finding, as words. */
    static String outcome(VerificationCheckOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** D-4F2A91 / R-0DE4B2 - the console's reference. */
    static String ref(String role, String userId) {
        if (userId == null) {
            return null;
        }
        String key = userId.replace("-", "");
        return ("DRIVER".equals(role) ? "D-" : "R-") + key.substring(0, Math.min(6, key.length())).toUpperCase(Locale.ROOT);
    }

    private static String name(String first, String last) {
        String n = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return n.isEmpty() ? null : n;
    }
}

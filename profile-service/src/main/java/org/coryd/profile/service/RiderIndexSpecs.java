package org.coryd.profile.service;

import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.enums.RiderIdentityStatus;
import org.coryd.profile.entity.RiderIndex;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The Riders Directory's filters and sorts on rider_index - all SQL. */
final class RiderIndexSpecs {

    private RiderIndexSpecs() {
    }

    static Specification<RiderIndex> status(RiderDirectoryStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("directoryStatus"), status);
    }

    static Specification<RiderIndex> identity(RiderIdentityStatus identity) {
        return (root, query, cb) -> identity == null ? null : cb.equal(root.get("identityStatus"), identity);
    }

    /** Completed a trip at or after since. */
    static Specification<RiderIndex> lastTripSince(Instant since) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("lastTripAt"), since);
    }

    /** Name (contains), the R- reference (starts with), or phone (4+ digits, contains). */
    static Specification<RiderIndex> search(String search) {
        return (root, query, cb) -> {
            DriverIndexSpecs.Term t = DriverIndexSpecs.Term.of(search, "r-");
            if (t == null) {
                return null;
            }
            List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
            any.add(cb.like(root.get("nameKey"), "%" + t.escaped() + "%", '\\'));
            if (!t.ref().isEmpty()) {
                any.add(cb.like(root.get("refKey"), t.ref() + "%", '\\'));
            }
            if (t.digits().length() >= 4) {
                any.add(cb.like(root.get("phoneDigits"), "%" + t.digits() + "%"));
            }
            return cb.or(any.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    /** Newest sign-up first when unsorted; missing values last either way; ties by rider ref. */
    static Specification<RiderIndex> sort(RiderDirectorySort sort, Sort.Direction direction) {
        if (sort == null) {
            return orderBy("joinedAt", true);
        }
        String column = switch (sort) {
            case RIDER_REF -> "refKey";
            case NAME -> "nameKey";
            case TRIPS -> "trips";
            case RETENTION, RELIABILITY -> "reliabilityPercent";
            case RATING -> "ratingAvg";
            case TOTAL_SPENT -> "totalSpent";
            case REPORTS -> "reportsFiled";
            case STATUS -> "directoryStatus";
            case JOINED -> "joinedAt";
            case LAST_TRIP -> "lastTripAt";
        };
        return orderBy(column, direction == Sort.Direction.DESC);
    }

    /** ORDER BY (column IS NULL), column, ref - in the query, as Spring Data drops nulls-last on specifications. Not on counts. */
    private static Specification<RiderIndex> orderBy(String column, boolean desc) {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                jakarta.persistence.criteria.Expression<Integer> missing = cb.<Integer>selectCase()
                        .when(cb.isNull(root.get(column)), 1).otherwise(0);
                query.orderBy(cb.asc(missing), desc ? cb.desc(root.get(column)) : cb.asc(root.get(column)), cb.asc(root.get("refKey")));
            }
            return null;
        };
    }
}

package org.coryd.profile.service;

import org.coryd.common.enums.DriverDirectorySort;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.VerificationQueueSort;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.profile.entity.DriverIndex;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The filters and sorts the Ops Console runs on driver_index - all of them become SQL, none run in memory. */
final class DriverIndexSpecs {

    private DriverIndexSpecs() {
    }

    /** Drivers with something waiting for ops, or a licence out with Smile ID since before cutoff. */
    static Specification<DriverIndex> inQueue(Instant cutoff) {
        return (root, query, cb) -> cb.or(cb.isTrue(root.get("inReview")), cb.lessThanOrEqualTo(root.get("licenceCheckStartedAt"), cutoff));
    }

    static Specification<DriverIndex> queueTab(VerificationQueueTab tab) {
        return (root, query, cb) -> tab == null ? null : cb.equal(root.get("queueTab"), tab);
    }

    static Specification<DriverIndex> status(DriverDirectoryStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("directoryStatus"), status);
    }

    /** Name (contains), or the D- reference (starts with). */
    static Specification<DriverIndex> nameOrRef(String search) {
        return (root, query, cb) -> {
            Term t = Term.of(search);
            if (t == null) {
                return null;
            }
            List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
            any.add(cb.like(root.get("nameKey"), "%" + t.escaped() + "%", '\\'));
            if (!t.ref().isEmpty()) {
                any.add(cb.like(root.get("refKey"), t.ref() + "%", '\\'));
            }
            return cb.or(any.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    /** nameOrRef, plus phone (4+ digits, contains) and plate (contains, spaces and dashes ignored). */
    static Specification<DriverIndex> directorySearch(String search) {
        return (root, query, cb) -> {
            Term t = Term.of(search);
            if (t == null) {
                return null;
            }
            List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
            any.add(nameOrRef(search).toPredicate(root, query, cb));
            if (t.digits().length() >= 4) {
                any.add(cb.like(root.get("phoneDigits"), "%" + t.digits() + "%"));
            }
            if (!t.ref().isEmpty()) {
                any.add(cb.like(root.get("plateKey"), "%" + t.ref() + "%", '\\'));
            }
            return cb.or(any.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    static Specification<DriverIndex> queueSort(VerificationQueueSort sort, Sort.Direction direction) {
        String column = switch (sort == null ? VerificationQueueSort.WAITING_SINCE : sort) {
            case WAITING_SINCE -> "waitingSince";
            case SUBMITTED_AT -> "submittedAt";
            case NAME -> "nameKey";
            case DRIVER_REF -> "refKey";
        };
        return orderBy(column, direction == Sort.Direction.DESC);
    }

    /** Newest sign-up first when unsorted; missing values last either way; ties by driver ref. */
    static Specification<DriverIndex> directorySort(DriverDirectorySort sort, Sort.Direction direction) {
        if (sort == null) {
            return orderBy("joinedAt", true);
        }
        String column = switch (sort) {
            case DRIVER_REF -> "refKey";
            case NAME -> "nameKey";
            case VEHICLE -> "vehicleKey";
            case TRIPS -> "trips";
            case RATING -> "ratingAvg";
            case EARNINGS -> "earningsNet";
            case RELIABILITY -> "reliabilityPercent";
            case STATUS -> "directoryStatus";
            case JOINED -> "joinedAt";
        };
        return orderBy(column, direction == Sort.Direction.DESC);
    }

    /**
     * ORDER BY (column IS NULL), column, ref - written into the query itself, because Spring Data drops Sort's
     * nulls-last on specification queries and Postgres puts NULLs first when descending. Skipped on the count query.
     */
    private static Specification<DriverIndex> orderBy(String column, boolean desc) {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                jakarta.persistence.criteria.Expression<Integer> missing = cb.<Integer>selectCase()
                        .when(cb.isNull(root.get(column)), 1).otherwise(0);
                query.orderBy(cb.asc(missing), desc ? cb.desc(root.get(column)) : cb.asc(root.get(column)), cb.asc(root.get("refKey")));
            }
            return null;
        };
    }

    /** A search term, lower case: as typed (escaped for LIKE), letters and digits only (ref and plate), digits only (phone). */
    record Term(String escaped, String ref, String digits) {
        static Term of(String search) {
            return of(search, "d-");
        }

        /** refPrefix is the reference's lower-case prefix ("d-", "r-"), dropped from a typed reference. */
        static Term of(String search, String refPrefix) {
            if (search == null || search.isBlank()) {
                return null;
            }
            String term = search.trim().toLowerCase(Locale.ROOT);
            String ref = (term.startsWith(refPrefix) ? term.substring(refPrefix.length()) : term).replaceAll("[^a-z0-9]", "");
            return new Term(term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"), ref, term.replaceAll("[^0-9]", ""));
        }
    }
}

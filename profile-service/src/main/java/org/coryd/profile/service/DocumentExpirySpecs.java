package org.coryd.profile.service;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryStatus;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverIndex;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Document Expiry Tracker's query: documents joined to driver_index for the driver's name, phone and reference, then
 * filtered, sorted and paged in the database. One specification builds it all, so the join is made once.
 */
final class DocumentExpirySpecs {

    static final Map<DocumentType, String> LABELS = Map.of(
            DocumentType.INSURANCE, "insurance certificate",
            DocumentType.ROADWORTHINESS_CERT, "roadworthiness certificate");

    private DocumentExpirySpecs() {
    }

    /** today and the last day that still counts as "expiring soon". */
    record Window(LocalDate today, LocalDate soonUntil) {
    }

    /**
     * tracked: documents with an expiry date (false: those without). notifiableOnly: expiring within the window or
     * expired. notifiedBefore, when set: only those not reminded since then.
     */
    record Filter(boolean tracked, DocumentExpiryTab tab, String search, boolean notifiableOnly, Instant notifiedBefore) {
        static Filter tracked(DocumentExpiryTab tab, String search) {
            return new Filter(true, tab, search, false, null);
        }
    }

    static Specification<DriverDocument> matching(Filter filter, Window window, DocumentExpirySort sort, Sort.Direction direction) {
        return (root, query, cb) -> {
            // A left join: a document whose driver isn't indexed yet still shows (and counts, and gets reminded) - it
            // just can't be found by name or phone until the index catches up.
            org.hibernate.query.criteria.JpaEntityJoin<DriverIndex> driver =
                    ((org.hibernate.query.criteria.JpaRoot<DriverDocument>) root).join(DriverIndex.class,
                            org.hibernate.query.sqm.tree.SqmJoinType.LEFT);
            driver.on(cb.equal(driver.get("driverId"), root.get("driverId")));
            Path<LocalDate> expires = root.get("expiresOn");
            List<Predicate> all = new ArrayList<>();
            all.add(filter.tracked() ? cb.isNotNull(expires) : cb.isNull(expires));
            if (filter.tab() != null) {
                all.add(switch (filter.tab()) {
                    case EXPIRED -> cb.lessThan(expires, window.today());
                    case EXPIRING_SOON -> cb.between(expires, window.today(), window.soonUntil());
                    case VERIFIED -> cb.and(cb.greaterThan(expires, window.soonUntil()), cb.equal(root.get("status"), VerificationStatus.APPROVED));
                });
            }
            if (filter.notifiableOnly()) {
                all.add(cb.lessThanOrEqualTo(expires, window.soonUntil()));
            }
            if (filter.notifiedBefore() != null) {
                all.add(cb.or(cb.isNull(root.get("lastNotifiedAt")), cb.lessThan(root.get("lastNotifiedAt"), filter.notifiedBefore())));
            }
            Predicate search = search(filter.search(), root, driver, cb);
            if (search != null) {
                all.add(search);
            }
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                Expression<?> column = column(sort, root, driver, window, cb);
                Expression<Integer> missing = cb.<Integer>selectCase().when(cb.isNull(column), 1).otherwise(0);
                boolean desc = direction == Sort.Direction.DESC;
                query.orderBy(cb.asc(missing), desc ? cb.desc(column) : cb.asc(column), cb.asc(driver.get("refKey")), cb.asc(root.get("documentType")));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Most urgent first when unsorted: the longest-expired, then the soonest to expire. */
    private static Expression<?> column(DocumentExpirySort sort, Root<DriverDocument> root, jakarta.persistence.criteria.From<?, DriverIndex> driver, Window window,
                                        jakarta.persistence.criteria.CriteriaBuilder cb) {
        return switch (sort == null ? DocumentExpirySort.EXPIRY_DATE : sort) {
            case DRIVER_REF -> driver.get("refKey");
            case NAME -> driver.get("nameKey");
            case EXPIRY_DATE, DAYS_LEFT -> root.get("expiresOn");
            case DOCUMENT -> root.get("documentType");
            case LAST_NOTIFIED -> root.get("lastNotifiedAt");
            case STATUS -> statusRank(root, window, cb);
        };
    }

    /** DocumentExpiryStatus's order, as SQL: expired, expiring soon, verified, not verified. */
    private static Expression<Integer> statusRank(Root<DriverDocument> root, Window window, jakarta.persistence.criteria.CriteriaBuilder cb) {
        Path<LocalDate> expires = root.get("expiresOn");
        return cb.<Integer>selectCase()
                .when(cb.lessThan(expires, window.today()), DocumentExpiryStatus.EXPIRED.ordinal())
                .when(cb.lessThanOrEqualTo(expires, window.soonUntil()), DocumentExpiryStatus.EXPIRING_SOON.ordinal())
                .when(cb.equal(root.get("status"), VerificationStatus.APPROVED), DocumentExpiryStatus.VERIFIED.ordinal())
                .otherwise(DocumentExpiryStatus.NOT_VERIFIED.ordinal());
    }

    /** Driver name (contains), D- reference (starts with), phone (4+ digits, contains), or the document's name. */
    private static Predicate search(String search, Root<DriverDocument> root, jakarta.persistence.criteria.From<?, DriverIndex> driver, jakarta.persistence.criteria.CriteriaBuilder cb) {
        DriverIndexSpecs.Term t = DriverIndexSpecs.Term.of(search);
        if (t == null) {
            return null;
        }
        List<Predicate> any = new ArrayList<>();
        any.add(cb.like(driver.get("nameKey"), "%" + t.escaped() + "%", '\\'));
        if (!t.ref().isEmpty()) {
            any.add(cb.like(driver.get("refKey"), t.ref() + "%", '\\'));
        }
        if (t.digits().length() >= 4) {
            any.add(cb.like(driver.get("phoneDigits"), "%" + t.digits() + "%"));
        }
        String typed = search.trim().toLowerCase(Locale.ROOT);
        List<DocumentType> types = typed.length() < 3 ? List.of() : Arrays.stream(DocumentType.values())
                .filter(type -> LABELS.getOrDefault(type, type.name().toLowerCase(Locale.ROOT)).contains(typed))
                .toList();
        if (!types.isEmpty()) {
            any.add(root.get("documentType").in(types));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }

    static DocumentExpiryStatus statusOf(DriverDocument doc, Window window) {
        LocalDate expires = doc.getExpiresOn();
        if (expires == null) {
            return doc.getStatus() == VerificationStatus.APPROVED ? DocumentExpiryStatus.VERIFIED : DocumentExpiryStatus.NOT_VERIFIED;
        }
        if (expires.isBefore(window.today())) {
            return DocumentExpiryStatus.EXPIRED;
        }
        if (!expires.isAfter(window.soonUntil())) {
            return DocumentExpiryStatus.EXPIRING_SOON;
        }
        return doc.getStatus() == VerificationStatus.APPROVED ? DocumentExpiryStatus.VERIFIED : DocumentExpiryStatus.NOT_VERIFIED;
    }
}

package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.IdentityReviewRole;
import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.IdentityActionRequest;
import org.coryd.profile.dto.IdentityCheckImages;
import org.coryd.profile.dto.IdentityReviewCounts;
import org.coryd.profile.dto.IdentityReviewDetail;
import org.coryd.profile.dto.IdentityReviewRow;
import org.coryd.profile.dto.VerificationDecisionHistoryItem;
import org.coryd.profile.entity.DriverIndex;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderIndex;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ops back office for identity (NIN) checks - riders, and drivers on either NIN path. Everyone who started a check is
 * listed by tab (pending, in review, failed, approved, locked out), searched, sorted and paged in the database; ops can
 * approve (overriding a Smile ID failure, with a note), reject (with the reason the person sees) or give the person their
 * tries back. Every action is written to the verification decision log as item IDENTITY.
 *
 * <p>The rules: a check Smile ID approved stays approved - the only one ops may reject is an approval on a partial name
 * match still on the review list. Anything else - failed, rejected, pending or in review - ops may approve.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityReviewService {

    /** decidedBy values of an automatic approval - the approvals ops can't take back. */
    static final Set<String> PROVIDER_DECIDERS = Set.of(IdentityVerificationDecider.SMILE_ID, DriverIdentity.DECIDED_BY, "PREMBLY");
    static final int REASON_MAX = 500;
    static final List<SmileIdCheckType> NIN_CHECKS = List.of(SmileIdCheckType.NIN, SmileIdCheckType.NIN_LOOKUP);
    private static final List<VerificationCheckType> NIN_RESULTS = List.of(VerificationCheckType.NIN_FACE, VerificationCheckType.NIN_LOOKUP);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final RiderIndexRepository riderIndexRepository;
    private final DriverIndexRepository driverIndexRepository;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final VerificationDecisionLogRepository decisionLogRepository;
    private final IdentityVerificationDecider decider;
    private final VerifiedIdentityService verifiedIdentityService;
    private final DriverIdentity driverIdentity;
    private final LicenceReviewService licenceReviewService;
    private final IdentityServiceClient identityServiceClient;
    private final SmileIdProperties properties;
    private final NamedParameterJdbcTemplate jdbc;

    // ---- list ---------------------------------------------------------------------------------------------------

    /**
     * Each person's tab, worked out in SQL from their check and their latest NIN Smile ID check, so tabs, search, sort and
     * paging all happen in one query. Name is the rider (else driver) profile name; phone comes from the directories' indexes.
     */
    private static final String BASE = """
            WITH base AS (
                SELECT iv.user_id, iv.submitted_at, iv.decided_at,
                       lower(trim(coalesce(rp.first_name, dp.first_name, '') || ' ' || coalesce(rp.last_name, dp.last_name, ''))) AS name_key,
                       coalesce(ri.phone_digits, '') AS rider_digits, coalesce(di.phone_digits, '') AS driver_digits,
                       rp.user_id IS NOT NULL AS is_rider, dp.user_id IS NOT NULL AS is_driver,
                       coalesce(s.attempts, 0) AS attempts_used,
                       CASE
                           WHEN iv.status = 'APPROVED' THEN 'APPROVED'
                           WHEN iv.status = 'IN_REVIEW' THEN 'IN_REVIEW'
                           WHEN iv.status = 'PENDING' AND dp.nin_status = 'APPROVED' THEN 'PENDING'
                           WHEN :rationed AND coalesce(s.attempts, 0) >=
                                (CASE WHEN s.check_type = 'NIN_LOOKUP' THEN :lookupMax ELSE :ninMax END) THEN 'LOCKED_OUT'
                           WHEN iv.status = 'REJECTED' OR s.retry_reason IS NOT NULL THEN 'FAILED'
                           ELSE 'PENDING'
                       END AS tab
                FROM profile.identity_verifications iv
                LEFT JOIN LATERAL (
                    SELECT c.attempts, c.check_type, c.retry_reason FROM profile.smile_id_checks c
                    WHERE c.user_id = iv.user_id AND c.check_type IN ('NIN', 'NIN_LOOKUP')
                    ORDER BY c.updated_at DESC LIMIT 1) s ON TRUE
                LEFT JOIN profile.rider_profiles rp ON rp.user_id = iv.user_id
                LEFT JOIN profile.driver_profiles dp ON dp.user_id = iv.user_id
                LEFT JOIN profile.rider_index ri ON ri.rider_id = iv.user_id
                LEFT JOIN profile.driver_index di ON di.driver_id = iv.user_id
            )
            """;

    @Transactional(readOnly = true)
    public PagedResponse<IdentityReviewRow> list(UserRole callerRole, IdentityReviewTab tab, String search, IdentityReviewRole role,
                                                 IdentityReviewSort sort, Sort.Direction direction, int page, int size) {
        requireOps(callerRole);
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = Math.max(0, page);
        MapSqlParameterSource params = params(search);
        String where = where(search, role, params) + (tab == null ? "" : " AND tab = :tab");
        if (tab != null) {
            params.addValue("tab", tab.name());
        }
        Long total = jdbc.queryForObject(BASE + "SELECT count(*) FROM base WHERE " + where, params, Long.class);
        params.addValue("limit", pageSize).addValue("offset", (long) pageNumber * pageSize);
        List<String> ids = jdbc.queryForList(BASE + "SELECT user_id FROM base WHERE " + where + " ORDER BY " + orderBy(sort, direction)
                + " LIMIT :limit OFFSET :offset", params, String.class);
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) ((totalElements + pageSize - 1) / pageSize);
        return new PagedResponse<>(rows(ids), pageNumber, pageSize, totalElements, totalPages, pageNumber >= totalPages - 1);
    }

    @Transactional(readOnly = true)
    public IdentityReviewCounts counts(UserRole callerRole, String search, IdentityReviewRole role) {
        requireOps(callerRole);
        MapSqlParameterSource params = params(search);
        String where = where(search, role, params);
        return jdbc.queryForObject(BASE + """
                SELECT count(*) AS all_count,
                       count(*) FILTER (WHERE tab = 'PENDING') AS pending,
                       count(*) FILTER (WHERE tab = 'IN_REVIEW') AS in_review,
                       count(*) FILTER (WHERE tab = 'FAILED') AS failed,
                       count(*) FILTER (WHERE tab = 'APPROVED') AS approved,
                       count(*) FILTER (WHERE tab = 'LOCKED_OUT') AS locked_out
                FROM base WHERE """ + " " + where, params, (rs, i) -> new IdentityReviewCounts(rs.getLong("all_count"),
                rs.getLong("pending"), rs.getLong("in_review"), rs.getLong("failed"), rs.getLong("approved"), rs.getLong("locked_out")));
    }

    private MapSqlParameterSource params(String search) {
        return new MapSqlParameterSource()
                .addValue("rationed", !properties.isSandbox())
                .addValue("ninMax", SmileIdCheck.MAX_ATTEMPTS)
                .addValue("lookupMax", properties.getNinLookupMaxAttempts());
    }

    /** Name contains the search; or, with 4+ digits in it, the phone does. */
    private static String where(String search, IdentityReviewRole role, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("TRUE");
        if (role == IdentityReviewRole.RIDER) {
            where.append(" AND is_rider");
        } else if (role == IdentityReviewRole.DRIVER) {
            where.append(" AND is_driver");
        }
        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String digits = term.replaceAll("[^0-9]", "");
            params.addValue("name", "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            where.append(" AND (name_key LIKE :name");
            if (digits.length() >= 4) {
                // 0803... and 234803... are the same phone - match on the part after the leading 0 / 234
                String local = digits.startsWith("234") ? digits.substring(3) : digits.startsWith("0") ? digits.substring(1) : digits;
                params.addValue("digits", "%" + local + "%");
                where.append(" OR rider_digits LIKE :digits OR driver_digits LIKE :digits");
            }
            where.append(")");
        }
        return where.toString();
    }

    /** Oldest submission first when unsorted; missing values last either way; ties by user id. */
    static String orderBy(IdentityReviewSort sort, Sort.Direction direction) {
        boolean desc = direction == Sort.Direction.DESC;
        String column = switch (sort == null ? IdentityReviewSort.SUBMITTED_AT : sort) {
            case SUBMITTED_AT -> "submitted_at";
            case NAME -> "name_key";
            case ATTEMPTS -> "attempts_used";
            case DECIDED_AT -> "decided_at";
            case STATUS -> "tab";
        };
        return column + (desc ? " DESC" : " ASC") + " NULLS LAST, user_id ASC";
    }

    /** The rows for one page, in the order the query returned them. */
    private List<IdentityReviewRow> rows(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, IdentityVerification> verifications = identityVerificationRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(IdentityVerification::getUserId, v -> v));
        Map<String, RiderProfile> riders = riderProfileRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(RiderProfile::getUserId, r -> r, (a, b) -> a));
        Map<String, DriverProfile> drivers = driverProfileRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(DriverProfile::getUserId, d -> d, (a, b) -> a));
        Map<String, RiderIndex> riderIndex = riderIndexRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(RiderIndex::getRiderId, r -> r));
        Map<String, DriverIndex> driverIndex = driverIndexRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(DriverIndex::getDriverId, d -> d));
        Map<String, SmileIdCheck> checks = smileIdCheckRepository.findByCheckTypeInAndUserIdIn(NIN_CHECKS, ids).stream()
                .collect(Collectors.toMap(SmileIdCheck::getUserId, c -> c, IdentityReviewService::newer));
        Map<String, VerificationCheck> results = verificationCheckRepository.findByDriverIdInAndCheckTypeIn(ids, NIN_RESULTS).stream()
                .collect(Collectors.toMap(VerificationCheck::getDriverId, c -> c,
                        (a, b) -> compareInstants(a.getCreatedAt(), b.getCreatedAt()) >= 0 ? a : b));
        return ids.stream().map(id -> {
            IdentityVerification v = verifications.get(id);
            if (v == null) {
                return null;
            }
            RiderProfile rider = riders.get(id);
            DriverProfile driver = drivers.get(id);
            SmileIdCheck check = checks.get(id);
            VerificationCheck result = results.get(id);
            int max = maxAttempts(check);
            return new IdentityReviewRow(id,
                    rider != null ? rider.getFirstName() : driver == null ? null : driver.getFirstName(),
                    rider != null ? rider.getLastName() : driver == null ? null : driver.getLastName(),
                    phone(riderIndex.get(id), driverIndex.get(id)),
                    rider != null && rider.getPhotoUrl() != null ? rider.getPhotoUrl() : driver == null ? null : driver.getPhotoUrl(),
                    rider != null, driver != null, tabOf(v.getStatus(), check, max, !properties.isSandbox(), ninAccepted(driver)), v.getStatus(),
                    v.getNinLast4(), v.getSubmittedAt(), v.getDecidedAt(), check == null ? 0 : check.getAttempts(),
                    check == null ? max : Math.max(0, max - check.getAttempts()),
                    check == null ? null : check.getRetryReason(), check != null && check.waitingForResult(),
                    result == null ? null : result.getOutcome(), result == null ? null : result.getProviderSummary());
        }).filter(Objects::nonNull).toList();
    }

    // ---- one person ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public IdentityReviewDetail detail(String userId, UserRole callerRole) {
        requireOps(callerRole);
        return build(find(userId));
    }

    @Transactional(readOnly = true)
    public List<VerificationDecisionHistoryItem> history(String userId, UserRole callerRole) {
        requireOps(callerRole);
        return decisionLogRepository.findByDriverIdAndItemOrderByCreatedAtDesc(userId, DecisionSubject.IDENTITY).stream()
                .map(VerificationDecisionHistoryItem::from).toList();
    }

    /** Fresh links to the images Smile ID captured for one of this person's identity checks. */
    @Transactional(readOnly = true)
    public IdentityCheckImages images(String userId, String checkId, UserRole callerRole) {
        requireOps(callerRole);
        VerificationCheck check = verificationCheckRepository.findById(checkId)
                .filter(c -> c.getDriverId().equals(userId) && NIN_RESULTS.contains(c.getCheckType()))
                .orElseThrow(() -> new ResourceNotFoundException("No identity check with that id for this person"));
        if (check.getCheckType() == VerificationCheckType.NIN_LOOKUP) {
            return new IdentityCheckImages(check.getId(), check.getProviderReference(), List.of(),
                    "A NIN lookup checks the number only - there's no selfie. The driver's face is checked on their licence");
        }
        LicenceReviewService.JobImages images = licenceReviewService.images(check, LicenceReviewService::ninKind);
        return new IdentityCheckImages(check.getId(), images.smileJobId(), images.images(), images.unavailableReason());
    }

    // ---- actions ------------------------------------------------------------------------------------------------

    /**
     * Ops approves the check - from in review, pending, failed (over Smile ID's result: a note is required) or rejected (a
     * note is required). A driver on the NIN lookup has their NIN accepted; they're verified once their licence is too.
     */
    @Transactional
    public IdentityReviewDetail approve(String userId, IdentityActionRequest request, String fallbackReviewer, String reviewerId,
                                        UserRole callerRole) {
        requireOps(callerRole);
        IdentityVerification verification = find(userId);
        boolean ninAccepted = ninAccepted(driverProfileRepository.findByUserId(userId).orElse(null));
        String blocked = approveBlockedReason(verification.getStatus(), ninAccepted);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        SmileIdCheck check = latestSmileCheck(userId);
        IdentityReviewTab tab = tabOf(verification.getStatus(), check, maxAttempts(check), !properties.isSandbox(), ninAccepted);
        String note = blankToNull(request == null ? null : request.note());
        if (note == null && approveNeedsNote(tab)) {
            throw new BadRequestException(verification.getStatus() == VerificationStatus.REJECTED
                    ? "Add a note saying why you're approving - it reverses the earlier rejection"
                    : "Add a note saying why you're approving - it overrides Smile ID's result");
        }
        String providerOutcome = providerOutcome(userId, check);
        boolean override = providerOutcome != null;
        VerificationDecisionAction action = override ? VerificationDecisionAction.OVERRIDE_APPROVED : VerificationDecisionAction.APPROVED;
        VerificationStatus previous = verification.getStatus();
        String reviewer = reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer);
        String decidedBy = reviewerId == null ? reviewer : reviewerId;

        verifiedIdentityService.applyFromLatestCheck(userId);
        verification.setDecisionNote(note);
        verification.setProviderOverridden(override);
        smileIdCheckRepository.findByCheckTypeInAndUserIdIn(NIN_CHECKS, List.of(userId)).forEach(c -> {
            if (c.getRetryReason() != null) {
                c.setRetryReason(null);
                smileIdCheckRepository.save(c);
            }
        });
        DriverProfile lookingUp = ninLookupDriver(userId);
        if (lookingUp != null) {
            // A driver's NIN lookup accepted - verified once their licence is too (already, or when it lands).
            lookingUp.setNinStatus(VerificationStatus.APPROVED);
            driverProfileRepository.save(lookingUp);
            if (lookingUp.getGovernmentIdStatus() == VerificationStatus.APPROVED) {
                decider.approveByOps(verification, decidedBy, action);
            } else {
                verification.setStatus(VerificationStatus.PENDING);
                verification.setDecidedAt(Instant.now());
                verification.setDecidedBy(decidedBy);
                verification.setRejectionReason(null);
                identityVerificationRepository.save(verification);
                decider.publishOpsAction(userId, driverIdentity.verified(userId), decidedBy, action, null);
            }
        } else {
            decider.approveByOps(verification, decidedBy, action);
        }
        record(userId, action, previous, verification.getStatus(), note, reviewer, reviewerId, override, providerOutcome);
        log.info("Identity check for {} {} by {} (Smile ID said {})", userId, action, reviewer, providerOutcome);
        return build(verification);
    }

    /**
     * Ops rejects the check, with the reason the person sees. A check Smile ID approved can't be rejected, unless it was
     * approved on a partial name match still on the review list - rejecting it takes it off that list.
     */
    @Transactional
    public IdentityReviewDetail reject(String userId, IdentityActionRequest request, String fallbackReviewer, String reviewerId,
                                       UserRole callerRole) {
        requireOps(callerRole);
        String reason = blankToNull(request == null ? null : request.reason());
        if (reason == null) {
            throw new BadRequestException("Give a reason for rejecting - the person sees it and needs it to put things right");
        }
        if (reason.length() > REASON_MAX) {
            throw new BadRequestException("Keep the reason to " + REASON_MAX + " characters - the person sees it");
        }
        IdentityVerification verification = find(userId);
        List<VerificationCheck> partials = openPartialMatches(userId);
        String blocked = rejectBlockedReason(verification.getStatus(), approvedByProvider(verification), !partials.isEmpty());
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        VerificationStatus previous = verification.getStatus();
        String reviewer = reviewer(request.reviewedBy(), fallbackReviewer);
        String decidedBy = reviewerId == null ? reviewer : reviewerId;

        verification.setDecisionNote(null);
        verification.setProviderOverridden(false);
        decider.rejectByOps(verification, reason, decidedBy);
        DriverProfile lookingUp = ninLookupDriver(userId);
        if (lookingUp == null) {
            lookingUp = driverProfileRepository.findByUserId(userId)
                    .filter(d -> d.getNinStatus() == VerificationStatus.APPROVED && hasNinLookup(userId)).orElse(null);
        }
        if (lookingUp != null) {
            lookingUp.setNinStatus(VerificationStatus.REJECTED);
            driverProfileRepository.save(lookingUp);
        }
        Instant now = Instant.now();
        partials.forEach(c -> {
            c.setReviewedAt(now);
            c.setReviewedBy(decidedBy);
            verificationCheckRepository.save(c);
        });
        record(userId, VerificationDecisionAction.REJECTED, previous, VerificationStatus.REJECTED, reason, reviewer, reviewerId, false,
                providerOutcome(userId, latestSmileCheck(userId)));
        log.info("Identity check for {} rejected by {}", userId, reviewer);
        return build(verification);
    }

    /** The person gets all their Smile ID tries back (both NIN paths) and the app lets them verify again. Status doesn't change. */
    @Transactional
    public IdentityReviewDetail resetAttempts(String userId, IdentityActionRequest request, String fallbackReviewer, String reviewerId,
                                              UserRole callerRole) {
        requireOps(callerRole);
        IdentityVerification verification = find(userId);
        List<SmileIdCheck> checks = smileIdCheckRepository.findByCheckTypeInAndUserIdIn(NIN_CHECKS, List.of(userId));
        int used = checks.stream().mapToInt(SmileIdCheck::getAttempts).sum() + verification.getAttempts();
        String blocked = resetBlockedReason(verification.getStatus(), used);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        String reviewer = reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer);
        String decidedBy = reviewerId == null ? reviewer : reviewerId;
        checks.forEach(c -> {
            c.setAttempts(0);
            smileIdCheckRepository.save(c);
        });
        verification.setAttempts(0);
        identityVerificationRepository.save(verification);
        String note = blankToNull(request == null ? null : request.note());
        record(userId, VerificationDecisionAction.ATTEMPTS_RESET, verification.getStatus(), verification.getStatus(),
                note != null ? note : used + " tries given back", reviewer, reviewerId, false, null);
        decider.publishOpsAction(userId, driverIdentity.verified(userId), decidedBy, VerificationDecisionAction.ATTEMPTS_RESET, null);
        log.info("Identity check tries for {} reset by {} ({} used)", userId, reviewer, used);
        return build(verification);
    }

    // ---- the rules ----------------------------------------------------------------------------------------------

    /**
     * Each person is in one tab, the first that matches - see IdentityReviewTab. Matches the list query's CASE. ninAccepted:
     * a driver whose NIN lookup is approved, waiting only for their licence - pending, whatever the lookup's tries.
     */
    static IdentityReviewTab tabOf(VerificationStatus status, SmileIdCheck check, int maxAttempts, boolean rationed, boolean ninAccepted) {
        if (status == VerificationStatus.APPROVED) {
            return IdentityReviewTab.APPROVED;
        }
        if (status == VerificationStatus.IN_REVIEW) {
            return IdentityReviewTab.IN_REVIEW;
        }
        if (status == VerificationStatus.PENDING && ninAccepted) {
            return IdentityReviewTab.PENDING;
        }
        if (rationed && check != null && check.getAttempts() >= maxAttempts) {
            return IdentityReviewTab.LOCKED_OUT;
        }
        if (status == VerificationStatus.REJECTED || (check != null && check.getRetryReason() != null)) {
            return IdentityReviewTab.FAILED;
        }
        return IdentityReviewTab.PENDING;
    }

    /**
     * Null when ops may approve. Not when it's approved already, nor for a driver whose NIN ops (or NIMC) already accepted -
     * their identity completes when their licence is approved, on the driver verification screen.
     */
    static String approveBlockedReason(VerificationStatus status, boolean ninAccepted) {
        if (status == VerificationStatus.APPROVED) {
            return "This person's identity is already verified";
        }
        if (status == VerificationStatus.PENDING && ninAccepted) {
            return "This driver's NIN is already confirmed - their identity is verified once their licence is approved";
        }
        return null;
    }

    /** A driver whose NIN lookup is approved. */
    static boolean ninAccepted(DriverProfile driver) {
        return driver != null && driver.getNinStatus() == VerificationStatus.APPROVED;
    }

    /** Approving a failed, locked-out or rejected check goes against a result that stands, so ops must say why. */
    static boolean approveNeedsNote(IdentityReviewTab tab) {
        return tab == IdentityReviewTab.FAILED || tab == IdentityReviewTab.LOCKED_OUT;
    }

    /**
     * Null when ops may reject. Rule: what Smile ID approved stays approved - except an approval on a partial name match
     * still on the review list. An approval ops made themselves may be reversed.
     */
    static String rejectBlockedReason(VerificationStatus status, boolean approvedByProvider, boolean partialMatchToReview) {
        if (status == VerificationStatus.REJECTED) {
            return "This identity check is already rejected";
        }
        if (status == VerificationStatus.APPROVED && approvedByProvider && !partialMatchToReview) {
            return "Smile ID verified this identity, so it stays approved - only an approval on a partial name match that "
                    + "hasn't been reviewed yet can be rejected";
        }
        return null;
    }

    static String resetBlockedReason(VerificationStatus status, int attemptsUsed) {
        if (status == VerificationStatus.APPROVED) {
            return "This person's identity is already verified - there are no tries to give back";
        }
        if (attemptsUsed <= 0) {
            return "No tries have been used - there's nothing to reset";
        }
        return null;
    }

    static boolean approvedByProvider(IdentityVerification verification) {
        return verification.getStatus() == VerificationStatus.APPROVED && PROVIDER_DECIDERS.contains(verification.getDecidedBy());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private IdentityReviewDetail build(IdentityVerification v) {
        String userId = v.getUserId();
        RiderProfile rider = riderProfileRepository.findByUserId(userId).orElse(null);
        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElse(null);
        String phone = phone(riderIndexRepository.findById(userId).orElse(null), driverIndexRepository.findById(userId).orElse(null));
        if (phone == null) {
            try {
                phone = identityServiceClient.fetchPhone(userId);
            } catch (Exception e) {
                log.warn("Couldn't load the phone for {}: {}", userId, e.getMessage());
            }
        }
        SmileIdCheck check = latestSmileCheck(userId);
        int max = maxAttempts(check);
        boolean rationed = !properties.isSandbox();
        IdentityReviewTab tab = tabOf(v.getStatus(), check, max, rationed, ninAccepted(driver));
        List<VerificationCheck> results = verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> NIN_RESULTS.contains(c.getCheckType()))
                .sorted(Comparator.comparing(VerificationCheck::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        boolean partialToReview = results.stream().anyMatch(IdentityReviewService::partialToReview);
        int used = check == null ? 0 : check.getAttempts();

        String approveBlocked = approveBlockedReason(v.getStatus(), ninAccepted(driver));
        String rejectBlocked = rejectBlockedReason(v.getStatus(), approvedByProvider(v), partialToReview);
        String resetBlocked = resetBlockedReason(v.getStatus(),
                smileIdCheckRepository.findByCheckTypeInAndUserIdIn(NIN_CHECKS, List.of(userId)).stream().mapToInt(SmileIdCheck::getAttempts).sum()
                        + v.getAttempts());

        return new IdentityReviewDetail(
                new IdentityReviewDetail.Person(userId,
                        rider != null ? rider.getFirstName() : driver == null ? null : driver.getFirstName(),
                        rider != null ? rider.getLastName() : driver == null ? null : driver.getLastName(),
                        phone, rider != null && rider.getPhotoUrl() != null ? rider.getPhotoUrl() : driver == null ? null : driver.getPhotoUrl(),
                        rider != null, driver != null),
                tab, v.getStatus(), v.getNinLast4(), v.getSubmittedAt(), v.getDecidedAt(), v.getDecidedBy(), v.getRejectionReason(),
                v.getDecisionNote(), v.isProviderOverridden(),
                v.getVerifiedFirstName() == null ? null : new IdentityReviewDetail.VerifiedRecord(v.getVerifiedFirstName(),
                        v.getVerifiedMiddleName(), v.getVerifiedLastName(), v.getVerifiedDateOfBirth()),
                driver == null || !hasNinLookup(userId) ? null : driver.getNinStatus(),
                new IdentityReviewDetail.Attempts(check == null ? null : check.getCheckType(), used, max, Math.max(0, max - used),
                        tab == IdentityReviewTab.LOCKED_OUT, check == null ? null : check.getRetryReason(),
                        check != null && check.waitingForResult(), check == null ? null : check.getStartedAt(), v.getAttempts()),
                v.getSelfieUrl(),
                results.stream().map(c -> new IdentityReviewDetail.Check(c.getId(), c.getCheckType(), c.getOutcome(), c.getNameMatchScore(),
                        c.getProviderSummary(), c.getProviderReference(), c.getCreatedAt(), partialToReview(c), c.getReviewedAt(),
                        c.getCheckType() == VerificationCheckType.NIN_FACE && c.getProviderReference() != null)).toList(),
                new IdentityReviewDetail.Actions(approveBlocked == null, approveBlocked, approveBlocked == null && approveNeedsNote(tab),
                        rejectBlocked == null, rejectBlocked, resetBlocked == null, resetBlocked),
                decisionLogRepository.findByDriverIdAndItemOrderByCreatedAtDesc(userId, DecisionSubject.IDENTITY).stream()
                        .map(VerificationDecisionHistoryItem::from).toList());
    }

    private IdentityVerification find(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("This person hasn't started an identity check"));
    }

    /** Their current NIN check with Smile ID - NIN + selfie, or a driver's NIN lookup - whichever moved last. */
    private SmileIdCheck latestSmileCheck(String userId) {
        return smileIdCheckRepository.findByCheckTypeInAndUserIdIn(NIN_CHECKS, List.of(userId)).stream()
                .reduce(IdentityReviewService::newer).orElse(null);
    }

    static SmileIdCheck newer(SmileIdCheck a, SmileIdCheck b) {
        return compareInstants(a.getUpdatedAt(), b.getUpdatedAt()) >= 0 ? a : b;
    }

    private static int compareInstants(Instant a, Instant b) {
        return Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).compare(a, b);
    }

    private int maxAttempts(SmileIdCheck check) {
        return maxAttempts(check, properties);
    }

    static int maxAttempts(SmileIdCheck check, SmileIdProperties properties) {
        return check != null && check.getCheckType() == SmileIdCheckType.NIN_LOOKUP ? properties.getNinLookupMaxAttempts() : SmileIdCheck.MAX_ATTEMPTS;
    }

    /**
     * What Smile ID last said when it didn't approve: the retry it asked for (FACE_MISMATCH, NIN_NOT_FOUND...) or the
     * finding it sent to ops (NAME_MISMATCH...). Null when it approved, never answered, or is checking right now.
     */
    private String providerOutcome(String userId, SmileIdCheck check) {
        if (check != null && check.waitingForResult()) {
            return null;
        }
        if (check != null && check.getRetryReason() != null) {
            return check.getRetryReason().name();
        }
        return verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> NIN_RESULTS.contains(c.getCheckType()))
                .max(Comparator.comparing(VerificationCheck::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(VerificationCheck::getOutcome)
                .filter(o -> o != VerificationCheckOutcome.MATCH && o != VerificationCheckOutcome.PARTIAL_NAME_MATCH)
                .map(Enum::name)
                .orElse(null);
    }

    private List<VerificationCheck> openPartialMatches(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream().filter(IdentityReviewService::partialToReview).toList();
    }

    private static boolean partialToReview(VerificationCheck c) {
        return NIN_RESULTS.contains(c.getCheckType()) && c.getOutcome() == VerificationCheckOutcome.PARTIAL_NAME_MATCH && c.getReviewedAt() == null;
    }

    /** A driver on the NIN lookup whose NIN isn't confirmed yet - ops' decision is on that lookup. */
    private DriverProfile ninLookupDriver(String userId) {
        return driverProfileRepository.findByUserId(userId)
                .filter(d -> d.getNinStatus() != VerificationStatus.APPROVED
                        && (d.getNinStatus() == VerificationStatus.IN_REVIEW || hasNinLookup(userId)))
                .orElse(null);
    }

    private boolean hasNinLookup(String userId) {
        return smileIdCheckRepository.findByUserIdAndCheckType(userId, SmileIdCheckType.NIN_LOOKUP).isPresent();
    }

    private void record(String userId, VerificationDecisionAction action, VerificationStatus previous, VerificationStatus status,
                        String note, String reviewer, String reviewerId, boolean override, String providerOutcome) {
        decisionLogRepository.save(VerificationDecisionLog.builder()
                .driverId(userId)
                .item(DecisionSubject.IDENTITY)
                .action(action)
                .previousStatus(previous)
                .status(status)
                .note(note)
                .decidedBy(reviewer)
                .decidedById(reviewerId)
                .overrodeProvider(override)
                .providerOutcome(providerOutcome)
                .build());
    }

    private static String phone(RiderIndex rider, DriverIndex driver) {
        return Optional.ofNullable(rider).map(RiderIndex::getPhone)
                .or(() -> Optional.ofNullable(driver).map(DriverIndex::getPhone)).orElse(null);
    }

    private static String reviewer(String given, String fallback) {
        String name = blankToNull(given);
        return name != null ? name : fallback == null || fallback.isBlank() ? "ops" : fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireOps(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can review identity checks");
        }
    }
}

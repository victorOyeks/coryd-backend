package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.dto.ItemApprovalRequest;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.ItemResubmissionRequest;
import org.coryd.profile.dto.ReviewDecisionRequest;
import org.coryd.profile.dto.ReviewMarkRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationDecisionHistoryItem;
import org.coryd.profile.dto.VerificationQueueItem;
import org.coryd.profile.dto.VerificationReviewResponse;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.VerificationReviewMark;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.coryd.profile.repository.VerificationReviewMarkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The ops review screen: a reviewer opens each document and marks it Verified or Flagged (a flag needs the reason
 * the driver will see), can reset a mark, then presses Approve Driver or Reject. The marks are kept until then, so
 * closing the screen or a second reviewer loses nothing. A mark only counts for the upload it was made on.
 */
@Service
@RequiredArgsConstructor
public class VerificationReviewService {

    /** A flag's note is the rejection reason the driver sees, which is capped at this. */
    static final int REASON_MAX = 255;

    private final VerificationAggregationService verificationAggregationService;
    private final VerificationReviewMarkRepository markRepository;
    private final VerificationDecisionLogRepository decisionLogRepository;

    @Transactional(readOnly = true)
    public VerificationReviewResponse review(String driverId, UserRole callerRole) {
        requireOps(callerRole);
        return build(driverId);
    }

    @Transactional
    public VerificationReviewResponse mark(String driverId, ReviewableItem item, ReviewMarkRequest request, String fallbackReviewer, UserRole callerRole) {
        requireOps(callerRole);
        VerificationQueueItem.Entry current = entry(driverId, item);
        if (!reviewable(current)) {
            throw new BadRequestException("The " + item.label().toLowerCase(Locale.ROOT) + " isn't waiting for review - it's "
                    + current.status().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        String note = blankToNull(request.note());
        if (request.mark() == ReviewMark.FLAGGED) {
            if (item == ReviewableItem.GOVERNMENT_ID) {
                String locked = verificationAggregationService.licenceLockedBySmileId(driverId);
                if (locked != null) {
                    throw new BadRequestException(locked);
                }
            }
            if (note == null) {
                throw new BadRequestException("Add a note to flag the " + item.label().toLowerCase(Locale.ROOT) + " - it's the reason the driver will see");
            }
            if (note.length() > REASON_MAX) {
                throw new BadRequestException("Keep the note to " + REASON_MAX + " characters - the driver sees it as the reason");
            }
        }
        VerificationReviewMark mark = markRepository.findByDriverIdAndItem(driverId, item)
                .orElseGet(() -> VerificationReviewMark.builder().driverId(driverId).item(item).build());
        mark.setMark(request.mark());
        mark.setNote(note);
        mark.setMarkedBy(reviewer(request.markedBy(), fallbackReviewer));
        mark.setItemSubmittedAt(current.submittedAt());
        markRepository.save(mark);
        return build(driverId);
    }

    /** "Reset to Default" - back to Unreviewed. */
    @Transactional
    public VerificationReviewResponse reset(String driverId, ReviewableItem item, UserRole callerRole) {
        requireOps(callerRole);
        markRepository.findByDriverIdAndItem(driverId, item).ifPresent(markRepository::delete);
        return build(driverId);
    }

    /**
     * Approve one document on its own - including a licence Smile ID failed (it asked the driver to try again), which ops
     * may approve over Smile ID's result. A note is required when the approval goes against a result that stands: Smile
     * ID's failed check, or an earlier rejection. The audit log records it as an override, with what Smile ID said.
     */
    @Transactional
    public VerificationStatusResponse approveItem(String driverId, ReviewableItem item, ItemApprovalRequest request, String fallbackReviewer,
                                                  String reviewerId, UserRole callerRole) {
        requireOps(callerRole);
        VerificationQueueItem.Entry current = entry(driverId, item);
        String providerOutcome = item == ReviewableItem.GOVERNMENT_ID ? verificationAggregationService.licenceProviderOutcome(driverId) : null;
        String blocked = approveItemBlockedReason(current, providerOutcome);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        String note = blankToNull(request == null ? null : request.note());
        if (note == null && approveItemNeedsNote(current, providerOutcome)) {
            throw new BadRequestException("Add a note saying why you're approving the " + item.label().toLowerCase(Locale.ROOT)
                    + " - it goes against " + (current.status() == VerificationStatus.REJECTED ? "its rejection" : "Smile ID's result"));
        }
        VerificationStatusResponse status = verificationAggregationService.decide(driverId, callerRole,
                new VerificationDecisionBatchRequest(List.of(new ItemDecision(item, VerificationStatus.APPROVED, null, note)),
                        reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer)),
                reviewerId, Map.of());
        markRepository.deleteByDriverIdAndItemIn(driverId, List.of(item));
        return status;
    }

    /**
     * Ask the driver to upload one document again, with the reason they'll see. The document becomes REJECTED at once (an
     * approved one loses its approval, as a flag + Save Changes does) and goes back in review when the new upload lands.
     * A licence Smile ID approved can't be sent back, unless it was approved on a partial name match ops hasn't reviewed.
     */
    @Transactional
    public VerificationStatusResponse requestResubmission(String driverId, ReviewableItem item, ItemResubmissionRequest request,
                                                          String fallbackReviewer, String reviewerId, UserRole callerRole) {
        requireOps(callerRole);
        String reason = blankToNull(request == null ? null : request.reason());
        if (reason == null) {
            throw new BadRequestException("Give the driver a reason so they know what to fix in the " + item.label().toLowerCase(Locale.ROOT));
        }
        if (reason.length() > REASON_MAX) {
            throw new BadRequestException("Keep the reason to " + REASON_MAX + " characters - the driver sees it");
        }
        VerificationQueueItem.Entry current = entry(driverId, item);
        String blocked = resubmissionBlockedReason(current,
                item == ReviewableItem.GOVERNMENT_ID ? verificationAggregationService.licenceLockedBySmileId(driverId) : null);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        VerificationStatusResponse status = verificationAggregationService.decide(driverId, callerRole,
                new VerificationDecisionBatchRequest(List.of(new ItemDecision(item, VerificationStatus.REJECTED, reason)),
                        reviewer(request.reviewedBy(), fallbackReviewer)),
                reviewerId, Map.of(item, org.coryd.common.enums.VerificationDecisionAction.RESUBMISSION_REQUESTED));
        markRepository.deleteByDriverIdAndItemIn(driverId, List.of(item));
        return status;
    }

    /** Null when ops can approve this document on its own; otherwise why not. */
    static String approveItemBlockedReason(VerificationQueueItem.Entry e, String providerOutcome) {
        String label = e.item().label().toLowerCase(Locale.ROOT);
        return switch (e.status()) {
            case APPROVED -> "The " + label + " is already approved";
            case EXPIRED -> "The " + label + " has expired - the driver must upload a current one";
            case PENDING -> e.awaitingProvider() || providerOutcome != null ? null : "The " + label + " hasn't been uploaded yet";
            case IN_REVIEW, REJECTED -> null;
        };
    }

    /** Approving over a standing result needs a note: Smile ID's failed check (the driver was asked to retry), or a rejection. */
    static boolean approveItemNeedsNote(VerificationQueueItem.Entry e, String providerOutcome) {
        return e.status() == VerificationStatus.REJECTED
                || (e.status() == VerificationStatus.PENDING && !e.awaitingProvider() && providerOutcome != null);
    }

    /** Null when ops can ask for this document again; otherwise why not. lockedBySmileId: licenceLockedBySmileId's answer. */
    static String resubmissionBlockedReason(VerificationQueueItem.Entry e, String lockedBySmileId) {
        String label = e.item().label().toLowerCase(Locale.ROOT);
        if (e.status() == VerificationStatus.REJECTED) {
            return "The " + label + " is already waiting for the driver to upload it again";
        }
        if (e.status() == VerificationStatus.PENDING && !e.awaitingProvider()) {
            return "The " + label + " hasn't been uploaded yet - there's nothing to send back";
        }
        return lockedBySmileId;
    }

    /** Approve Driver: every item is already approved or marked Verified on its current upload, and nothing is flagged. */
    @Transactional
    public VerificationStatusResponse approve(String driverId, ReviewDecisionRequest request, String fallbackReviewer, UserRole callerRole) {
        return approve(driverId, request, fallbackReviewer, null, callerRole);
    }

    @Transactional
    public VerificationStatusResponse approve(String driverId, ReviewDecisionRequest request, String fallbackReviewer, String reviewerId,
                                              UserRole callerRole) {
        requireOps(callerRole);
        List<VerificationQueueItem.Entry> items = verificationAggregationService.currentItems(driverId);
        Map<ReviewableItem, VerificationReviewMark> marks = currentMarks(driverId, items);
        String blocked = approveBlockedReason(items, marks);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        String note = blankToNull(request == null ? null : request.note());
        List<ItemDecision> decisions = items.stream()
                .filter(VerificationQueueItem.Entry::awaitingOps)
                .map(e -> new ItemDecision(e.item(), VerificationStatus.APPROVED, null, firstNonNull(marks.get(e.item()).getNote(), note)))
                .toList();
        return decide(driverId, decisions, reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer), reviewerId, callerRole, marks.keySet());
    }

    /**
     * Reject: needs at least one flagged item. Flagged items are rejected with their notes as the reasons; items marked
     * Verified are approved at the same time; anything not yet looked at stays waiting.
     */
    @Transactional
    public VerificationStatusResponse reject(String driverId, ReviewDecisionRequest request, String fallbackReviewer, UserRole callerRole) {
        return reject(driverId, request, fallbackReviewer, null, callerRole);
    }

    @Transactional
    public VerificationStatusResponse reject(String driverId, ReviewDecisionRequest request, String fallbackReviewer, String reviewerId,
                                             UserRole callerRole) {
        requireOps(callerRole);
        List<VerificationQueueItem.Entry> items = verificationAggregationService.currentItems(driverId);
        Map<ReviewableItem, VerificationReviewMark> marks = currentMarks(driverId, items);
        if (marks.values().stream().noneMatch(m -> m.getMark() == ReviewMark.FLAGGED)) {
            throw new BadRequestException("Flag at least one document, with the reason, to reject");
        }
        List<ItemDecision> decisions = new ArrayList<>();
        for (VerificationQueueItem.Entry e : items) {
            VerificationReviewMark mark = marks.get(e.item());
            if (mark == null) {
                continue;
            }
            ItemDecision decision = toDecision(e, mark);
            if (decision != null) {
                decisions.add(decision);
            }
        }
        return decide(driverId, decisions, reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer), reviewerId, callerRole, marks.keySet());
    }

    /**
     * Save Changes (the Drivers Directory's documents): applies whatever is marked, with no all-or-nothing rule.
     * Flagged items are rejected - an approved one loses its approval and the driver must re-upload it; items waiting for
     * ops that are marked Verified are approved. Unmarked items, and approved ones marked Verified, stay as they are.
     */
    @Transactional
    public VerificationStatusResponse save(String driverId, ReviewDecisionRequest request, String fallbackReviewer, UserRole callerRole) {
        return save(driverId, request, fallbackReviewer, null, callerRole);
    }

    @Transactional
    public VerificationStatusResponse save(String driverId, ReviewDecisionRequest request, String fallbackReviewer, String reviewerId,
                                           UserRole callerRole) {
        requireOps(callerRole);
        List<VerificationQueueItem.Entry> items = verificationAggregationService.currentItems(driverId);
        Map<ReviewableItem, VerificationReviewMark> marks = currentMarks(driverId, items);
        List<ItemDecision> decisions = items.stream()
                .filter(e -> marks.containsKey(e.item()))
                .map(e -> toDecision(e, marks.get(e.item())))
                .filter(Objects::nonNull)
                .toList();
        if (decisions.isEmpty()) {
            throw new BadRequestException("Nothing to save - verify or flag a document first");
        }
        return decide(driverId, decisions, reviewer(request == null ? null : request.reviewedBy(), fallbackReviewer), reviewerId, callerRole, marks.keySet());
    }

    /** A flag rejects; Verified approves only what isn't approved already. */
    private static ItemDecision toDecision(VerificationQueueItem.Entry e, VerificationReviewMark mark) {
        if (mark.getMark() == ReviewMark.FLAGGED) {
            return new ItemDecision(e.item(), VerificationStatus.REJECTED, mark.getNote(), null);
        }
        return e.status() == VerificationStatus.APPROVED ? null : new ItemDecision(e.item(), VerificationStatus.APPROVED, null, mark.getNote());
    }

    @Transactional(readOnly = true)
    public List<VerificationDecisionHistoryItem> history(String driverId, UserRole callerRole) {
        requireOps(callerRole);
        return decisionLogRepository.findByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(VerificationDecisionHistoryItem::from)
                .toList();
    }

    /** Every mark that was in play is cleared once the decision lands - including Verified on items left as they were. */
    private VerificationStatusResponse decide(String driverId, List<ItemDecision> decisions, String reviewer, String reviewerId,
                                              UserRole callerRole, java.util.Collection<ReviewableItem> markedItems) {
        VerificationStatusResponse status = decisions.isEmpty() ? verificationAggregationService.getStatus(driverId)
                : verificationAggregationService.decide(driverId, callerRole, new VerificationDecisionBatchRequest(decisions, reviewer),
                        reviewerId, Map.of());
        java.util.Set<ReviewableItem> clear = new java.util.HashSet<>(markedItems);
        decisions.forEach(d -> clear.add(d.item()));
        if (!clear.isEmpty()) {
            markRepository.deleteByDriverIdAndItemIn(driverId, clear);
        }
        return status;
    }

    private VerificationReviewResponse build(String driverId) {
        List<VerificationQueueItem.Entry> items = verificationAggregationService.currentItems(driverId);
        Map<ReviewableItem, VerificationReviewMark> marks = currentMarks(driverId, items);
        String licenceOutcome = verificationAggregationService.licenceProviderOutcome(driverId);
        String licenceLocked = verificationAggregationService.licenceLockedBySmileId(driverId);
        List<VerificationReviewResponse.ItemReview> reviews = items.stream().map(e -> {
            VerificationReviewMark m = marks.get(e.item());
            boolean licence = e.item() == ReviewableItem.GOVERNMENT_ID;
            String providerOutcome = licence ? licenceOutcome : null;
            String approveBlocked = approveItemBlockedReason(e, providerOutcome);
            String resubmissionBlocked = resubmissionBlockedReason(e, licence ? licenceLocked : null);
            return new VerificationReviewResponse.ItemReview(e.item(), e.item().label(), e.status(), e.submittedAt(), e.resubmitted(),
                    m == null ? null : m.getMark(), m == null ? null : m.getNote(), m == null ? null : m.getMarkedBy(),
                    m == null ? null : m.getUpdatedAt(), e.awaitingProvider(),
                    approveBlocked == null, approveBlocked, approveBlocked == null && approveItemNeedsNote(e, providerOutcome),
                    resubmissionBlocked == null, resubmissionBlocked, providerOutcome, licence && licenceLocked != null);
        }).toList();
        String blocked = approveBlockedReason(items, marks);
        boolean canReject = marks.values().stream().anyMatch(m -> m.getMark() == ReviewMark.FLAGGED);
        boolean canSave = items.stream().anyMatch(e -> marks.containsKey(e.item()) && toDecision(e, marks.get(e.item())) != null);
        return new VerificationReviewResponse(reviews, blocked == null, blocked, canReject, canSave);
    }

    /**
     * What a reviewer can mark: anything waiting for ops, and a document already approved (or expired) - flagging one of
     * those and saving takes the approval back. A rejected or never-uploaded item waits for the driver instead.
     */
    static boolean reviewable(VerificationQueueItem.Entry e) {
        return e.awaitingOps() || e.status() == VerificationStatus.APPROVED || e.status() == VerificationStatus.EXPIRED;
    }

    /** Marks still on the upload they were made on, for items that can still be marked - the rest no longer apply. */
    private Map<ReviewableItem, VerificationReviewMark> currentMarks(String driverId, List<VerificationQueueItem.Entry> items) {
        Map<ReviewableItem, VerificationQueueItem.Entry> byItem = items.stream()
                .collect(Collectors.toMap(VerificationQueueItem.Entry::item, e -> e));
        return markRepository.findByDriverId(driverId).stream()
                .filter(m -> {
                    VerificationQueueItem.Entry e = byItem.get(m.getItem());
                    return e != null && reviewable(e) && Objects.equals(e.submittedAt(), m.getItemSubmittedAt());
                })
                .collect(Collectors.toMap(VerificationReviewMark::getItem, m -> m));
    }

    /** Null when Approve Driver can be pressed; otherwise what's in the way, in the order to fix it. */
    static String approveBlockedReason(List<VerificationQueueItem.Entry> items, Map<ReviewableItem, VerificationReviewMark> marks) {
        List<String> missing = labels(items.stream().filter(e -> e.status() == VerificationStatus.PENDING && !e.awaitingProvider()).toList());
        if (!missing.isEmpty()) {
            return "Not uploaded yet: " + String.join(", ", missing);
        }
        List<String> rejected = labels(items.stream().filter(e -> e.status() == VerificationStatus.REJECTED).toList());
        if (!rejected.isEmpty()) {
            return "Waiting for the driver to re-upload: " + String.join(", ", rejected);
        }
        List<String> flagged = labels(items.stream().filter(e -> marks.containsKey(e.item()) && marks.get(e.item()).getMark() == ReviewMark.FLAGGED).toList());
        if (!flagged.isEmpty()) {
            return "Flagged: " + String.join(", ", flagged) + " - reject, or reset the flag";
        }
        List<String> unreviewed = labels(items.stream().filter(e -> e.awaitingOps() && !marks.containsKey(e.item())).toList());
        if (!unreviewed.isEmpty()) {
            return "Not reviewed yet: " + String.join(", ", unreviewed);
        }
        if (items.stream().noneMatch(VerificationQueueItem.Entry::awaitingOps)) {
            return "Nothing is waiting for review";
        }
        return null;
    }

    private VerificationQueueItem.Entry entry(String driverId, ReviewableItem item) {
        return verificationAggregationService.currentItems(driverId).stream().filter(e -> e.item() == item).findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown item " + item));
    }

    private static List<String> labels(List<VerificationQueueItem.Entry> entries) {
        return entries.stream().map(e -> e.item().label()).toList();
    }

    private static String reviewer(String given, String fallback) {
        String name = blankToNull(given);
        return name != null ? name : fallback == null || fallback.isBlank() ? "ops" : fallback;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireOps(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can review driver verification");
        }
    }
}

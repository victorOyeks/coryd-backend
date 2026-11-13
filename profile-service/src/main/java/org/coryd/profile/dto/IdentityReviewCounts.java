package org.coryd.profile.dto;

/** The ops identity (NIN) list's tab badges - each follows the same search and role as the list. */
public record IdentityReviewCounts(long all, long pending, long inReview, long failed, long approved, long lockedOut) {
}

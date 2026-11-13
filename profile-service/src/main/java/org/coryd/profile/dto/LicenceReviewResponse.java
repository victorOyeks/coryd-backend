package org.coryd.profile.dto;

import java.time.Instant;
import java.util.List;

/**
 * The driver's licence as Smile ID saw it, for the ops review screen: what it read off the licence, and fresh links to
 * the images it captured (they expire within hours - fetch again rather than keeping them). imagesUnavailableReason is
 * set when no images could be fetched; the licence details still show.
 */
public record LicenceReviewResponse(
        String smileJobId,
        Instant checkedAt,
        String outcome,
        String summary,
        LicenceDetails licence,
        List<Image> images,
        String imagesUnavailableReason
) {
    /** What Smile ID read off the licence - the number masked ("•••••••5678") and the date of birth as a year; the full values
     * are an audited reveal (POST /ops/users/{id}/reveal, docs/ops-console/privacy.md). */
    public record LicenceDetails(String fullName, String licenceNumberMasked, Integer birthYear, String expirationDate) {
    }

    /** kind: LICENCE_FRONT, LICENCE_BACK, SELFIE or OTHER. name is Smile ID's own name for it. */
    public record Image(String kind, String name, String url) {
    }
}

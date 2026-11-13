package org.coryd.profile.dto;

import java.util.List;

/**
 * Fresh links to the images Smile ID captured for one identity check (kind SELFIE, ID_PHOTO or OTHER). They expire within
 * hours - fetch again each time. imagesUnavailableReason says why there are none.
 */
public record IdentityCheckImages(String checkId, String smileJobId, List<LicenceReviewResponse.Image> images, String imagesUnavailableReason) {
}

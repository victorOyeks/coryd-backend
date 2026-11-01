package org.coryd.ops.dto;

import org.coryd.common.enums.DocumentExpiryStatus;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the Document Expiry Tracker. daysLeft is negative once expired (-3 is "3 days ago"). verifiedAt is when ops
 * approved the document. canNotify is false for a document not yet within 14 days of expiry, or one reminded within the
 * cool-down (notifiedRecently - show "Notified").
 */
public record DocumentExpiryItem(
        String documentId,
        DriverSummary driver,
        DocumentType documentType,
        String documentLabel,
        LocalDate expiresOn,
        long daysLeft,
        DocumentExpiryStatus status,
        VerificationStatus verificationStatus,
        Instant verifiedAt,
        Instant lastNotifiedAt,
        String lastNotifiedBy,
        boolean notifiedRecently,
        boolean canNotify
) {
}

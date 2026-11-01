package org.coryd.profile.dto;

import org.coryd.common.enums.DocumentExpiryStatus;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the Document Expiry Tracker. daysLeft counts from today in Lagos - negative once expired (-3 is "3 days
 * ago"). verifiedAt is when ops approved it, while it's approved. notifiable: expiring within 14 days or expired, so a
 * reminder can be sent; notifiedRecently: one went within the cool-down, so another can't yet.
 */
public record DocumentExpiryView(
        String documentId,
        String driverId,
        String firstName,
        String lastName,
        String phone,
        String photoUrl,
        DocumentType documentType,
        LocalDate expiresOn,
        long daysLeft,
        DocumentExpiryStatus status,
        VerificationStatus verificationStatus,
        Instant verifiedAt,
        Instant lastNotifiedAt,
        String lastNotifiedBy,
        boolean notifiable,
        boolean notifiedRecently
) {
}

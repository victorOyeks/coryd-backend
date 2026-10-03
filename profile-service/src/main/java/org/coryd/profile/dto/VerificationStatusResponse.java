package org.coryd.profile.dto;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Aggregates every independently-reviewable driver item into one response - the single call the driver app's pending/approved/changes-needed and verification-status screens all bind to. verifiedSince is the first time this driver ever reached overallStatus APPROVED - it doesn't move if they're later rejected on one item and re-approved, same idea as a "member since" date. Null until the driver has been approved at least once. */
public record VerificationStatusResponse(
        DriverDetails driver,
        Instant waitingSince,
        ItemStatus governmentId,
        VehicleRegistrationStatus vehicleRegistration,
        List<DocumentStatus> documents,
        VerificationStatus overallStatus,
        Instant verifiedSince,
        List<ProviderCheck> providerChecks
) {
    /** What an identity provider said about this driver, and when. Empty when the provider is off
     * or has not been asked yet. Advisory only: ops still decides, this is the evidence. */
    public record ProviderCheck(VerificationCheckType type, VerificationCheckOutcome outcome,
                                Double nameMatchScore, String summary, Instant checkedAt) {
    }

    /** Who is being reviewed - licenceNumber is the driver's licence number, the one checked against FRSC. */
    public record DriverDetails(String firstName, String lastName, String photoUrl, String licenceNumber, LocalDate dateOfBirth) {
    }

    public record ItemStatus(VerificationStatus status, String rejectionReason, String url,
                             Instant submittedAt, boolean resubmitted) {
    }

    public record VehicleRegistrationStatus(VerificationStatus status, String rejectionReason, VehicleFields vehicle,
                                            Instant submittedAt, boolean resubmitted) {
    }

    public record VehicleFields(String make, String model, Integer year, String color, String plateNumber,
                                 String exteriorPhotoUrl, String interiorPhotoUrl, boolean hasAC, boolean acConfirmedWorking) {
    }

    public record DocumentStatus(DocumentType type, VerificationStatus status, String rejectionReason, LocalDate expiresOn, String documentUrl,
                                 Instant submittedAt, boolean resubmitted) {
    }
}

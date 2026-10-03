package org.coryd.ops.dto;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One driver's four reviewable items, their documents and automatic check results - profile-service's shape,
 * with the driver's ref and phone added here. */
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
    public record ProviderCheck(VerificationCheckType type, VerificationCheckOutcome outcome,
                                Double nameMatchScore, String summary, Instant checkedAt) {
    }

    public record DriverDetails(String id, String ref, String firstName, String lastName, String phone, String photoUrl,
                                String licenceNumber, LocalDate dateOfBirth) {
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

    public record DocumentStatus(DocumentType type, VerificationStatus status, String rejectionReason, LocalDate expiresOn,
                                 String documentUrl, Instant submittedAt, boolean resubmitted) {
    }

    public VerificationStatusResponse withDriver(DriverDetails details) {
        return new VerificationStatusResponse(details, waitingSince, governmentId, vehicleRegistration, documents,
                overallStatus, verifiedSince, providerChecks);
    }
}

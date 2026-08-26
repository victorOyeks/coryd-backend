package org.coryd.profile.dto;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;

import java.time.LocalDate;
import java.util.List;

/** Aggregates every independently-reviewable driver item into one response - the single call the driver app's pending/approved/changes-needed and verification-status screens all bind to. */
public record VerificationStatusResponse(
        ItemStatus governmentId,
        ItemStatus selfie,
        VehicleRegistrationStatus vehicleRegistration,
        List<DocumentStatus> documents,
        VerificationStatus overallStatus
) {
    public record ItemStatus(VerificationStatus status, String rejectionReason, String url) {
    }

    public record VehicleRegistrationStatus(VerificationStatus status, String rejectionReason, VehicleFields vehicle) {
    }

    public record VehicleFields(String make, String model, Integer year, String color, String plateNumber,
                                 String exteriorPhotoUrl, String interiorPhotoUrl, boolean hasAC, boolean acConfirmedWorking) {
    }

    public record DocumentStatus(DocumentType type, VerificationStatus status, String rejectionReason, LocalDate expiresOn, String documentUrl) {
    }
}

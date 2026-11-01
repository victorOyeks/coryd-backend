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
        List<ProviderCheck> providerChecks,
        /** The reviewer's marks and whether Approve Driver / Reject can be pressed - null if it couldn't be loaded. */
        VerificationReview review
) {
    /** The earliest submission among the four items - "Submitted" in the review summary. */
    @com.fasterxml.jackson.annotation.JsonProperty("submittedAt")
    public Instant submittedAt() {
        return java.util.stream.Stream.of(
                        governmentId == null ? null : governmentId.submittedAt(),
                        vehicleRegistration == null ? null : vehicleRegistration.submittedAt())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), list -> {
                    if (documents != null) {
                        documents.stream().map(DocumentStatus::submittedAt).filter(java.util.Objects::nonNull).forEach(list::add);
                    }
                    return list.stream().min(java.util.Comparator.naturalOrder()).orElse(null);
                }));
    }

    /** The insurance certificate's expiry - shown with the vehicle details. Null until insurance is uploaded. */
    @com.fasterxml.jackson.annotation.JsonProperty("insuranceExpiresOn")
    public LocalDate insuranceExpiresOn() {
        return documents == null ? null : documents.stream().filter(d -> d.type() == DocumentType.INSURANCE)
                .map(DocumentStatus::expiresOn).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** providerReference: the provider's own id for the check - for Smile ID, the job ID to open in its portal. */
    public record ProviderCheck(VerificationCheckType type, VerificationCheckOutcome outcome,
                                Double nameMatchScore, String summary, Instant checkedAt, String providerReference) {
    }

    public record DriverDetails(String id, String ref, String firstName, String lastName, String phone, String photoUrl,
                                String licenceNumber, LocalDate dateOfBirth) {
    }

    /** awaitingProvider: the licence is with Smile ID and its result hasn't come - ops may still decide it; the result overrides. */
    public record ItemStatus(VerificationStatus status, String rejectionReason, String url,
                             Instant submittedAt, boolean resubmitted, boolean awaitingProvider) {

        @com.fasterxml.jackson.annotation.JsonProperty("file")
        public FileLink file() {
            return FileLink.of(url);
        }
    }

    public record VehicleRegistrationStatus(VerificationStatus status, String rejectionReason, VehicleFields vehicle,
                                            Instant submittedAt, boolean resubmitted) {
    }

    public record VehicleFields(String make, String model, Integer year, String color, String plateNumber,
                                String exteriorPhotoUrl, String interiorPhotoUrl, boolean hasAC, boolean acConfirmedWorking) {

        @com.fasterxml.jackson.annotation.JsonProperty("exteriorPhoto")
        public FileLink exteriorPhoto() {
            return FileLink.of(exteriorPhotoUrl);
        }

        @com.fasterxml.jackson.annotation.JsonProperty("interiorPhoto")
        public FileLink interiorPhoto() {
            return FileLink.of(interiorPhotoUrl);
        }
    }

    public record DocumentStatus(DocumentType type, VerificationStatus status, String rejectionReason, LocalDate expiresOn,
                                 String documentUrl, Instant submittedAt, boolean resubmitted) {

        @com.fasterxml.jackson.annotation.JsonProperty("file")
        public FileLink file() {
            return FileLink.of(documentUrl);
        }
    }

    public VerificationStatusResponse withDriver(DriverDetails details) {
        return new VerificationStatusResponse(details, waitingSince, governmentId, vehicleRegistration, documents,
                overallStatus, verifiedSince, providerChecks, review);
    }

    public VerificationStatusResponse withReview(VerificationReview review) {
        return new VerificationStatusResponse(driver, waitingSince, governmentId, vehicleRegistration, documents,
                overallStatus, verifiedSince, providerChecks, review);
    }
}

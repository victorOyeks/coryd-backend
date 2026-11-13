package org.coryd.profile.dto;

import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything profile-service holds about one person, for the ops console's driver and rider profiles (ops-service adds
 * identity-service's account half). driver / rider are null when the person has no such profile; identity is null when
 * they never started an identity (NIN) check.
 */
public record OpsAccountProfile(
        String userId,
        Driver driver,
        Rider rider,
        List<VehicleView> vehicles,
        List<Contact> emergencyContacts,
        Identity identity
) {
    public record Driver(String firstName, String lastName, String email, String photoUrl, LocalDate dateOfBirth, String licenceNumber,
                         VerificationStatus verificationStatus, Instant verifiedSince, List<Item> items, LicenceAttempts licenceAttempts) {
    }

    /** One of the driver's four documents. awaitingProvider: the licence is still with Smile ID. */
    public record Item(ReviewableItem item, String label, VerificationStatus status, boolean awaitingProvider, Instant submittedAt,
                       boolean resubmitted) {
    }

    public record Rider(String firstName, String lastName, String email, String photoUrl) {
    }

    /** primary: the vehicle the verification screens review (the first one registered). */
    public record VehicleView(String id, String make, String model, Integer year, String color, String plateNumber, Integer seatCapacity,
                              boolean hasAC, VerificationStatus status, String rejectionReason, boolean primary) {
    }

    public record Contact(String id, String name, String phone, boolean primary) {
    }

    public record Identity(VerificationStatus status, Instant submittedAt, Instant decidedAt, String rejectionReason) {
    }
}

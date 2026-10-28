package org.coryd.profile.dto;

/** What trip-service's route-posting gate needs: identity (the licence) and vehicle, both as submitted and as approved,
 * and whether the driver is verified as a person - DriverIdentity.verified. */
public record DriverPrerequisiteStatus(
        boolean identitySubmitted,
        boolean vehicleSubmitted,
        boolean identityApproved,
        boolean vehicleApproved,
        boolean identityVerified
) {
}

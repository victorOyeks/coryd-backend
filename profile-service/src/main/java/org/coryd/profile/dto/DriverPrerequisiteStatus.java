package org.coryd.profile.dto;

/** What trip-service's route-posting gate needs: identity and vehicle, both as submitted and as approved. */
public record DriverPrerequisiteStatus(
        boolean identitySubmitted,
        boolean vehicleSubmitted,
        boolean identityApproved,
        boolean vehicleApproved
) {
}

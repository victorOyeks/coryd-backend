package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.Vehicle;

/** Lightweight, search-result-safe projection of Vehicle. */
public record VehicleSummary(String id, String make, String model, String plateNumber, boolean hasAC,
                              VerificationStatus verificationStatus) {

    public static VehicleSummary from(Vehicle vehicle) {
        return new VehicleSummary(vehicle.getId(), vehicle.getMake(), vehicle.getModel(),
                vehicle.getPlateNumber(), vehicle.isHasAC(), vehicle.getVerificationStatus());
    }
}

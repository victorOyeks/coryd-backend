package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.Vehicle;

/** Lightweight, search-result-safe projection of Vehicle. */
public record VehicleSummary(String id, String make, String model, String plateNumber, boolean hasAC,
                              Integer seatCapacity, VerificationStatus verificationStatus, Integer year, String color,
                              /* whose car it is - trip-service checks a route is posted on the driver's own vehicle */
                              String driverId) {

    public static VehicleSummary from(Vehicle vehicle) {
        return new VehicleSummary(vehicle.getId(), vehicle.getMake(), vehicle.getModel(),
                vehicle.getPlateNumber(), vehicle.isHasAC(), vehicle.getSeatCapacity(), vehicle.getVerificationStatus(),
                vehicle.getYear(), vehicle.getColor(), vehicle.getDriverId());
    }
}

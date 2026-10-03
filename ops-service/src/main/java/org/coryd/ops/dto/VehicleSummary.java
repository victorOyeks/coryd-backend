package org.coryd.ops.dto;

public record VehicleSummary(
        String id,
        String make,
        String model,
        Integer year,
        String color,
        String plateNumber
) {
}

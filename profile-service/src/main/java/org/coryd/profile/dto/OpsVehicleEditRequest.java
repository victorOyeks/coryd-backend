package org.coryd.profile.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Ops changing a driver's vehicle. Fields left out (null) stay as they are; at least one must be sent. */
public record OpsVehicleEditRequest(@Size(max = 50) String make,
                                    @Size(max = 50) String model,
                                    @Min(1980) @Max(2100) Integer year,
                                    @Size(max = 30) String color,
                                    @Size(max = 20) String plateNumber,
                                    @Min(1) @Max(14) Integer seatCapacity) {
}

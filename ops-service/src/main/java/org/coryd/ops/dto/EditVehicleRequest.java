package org.coryd.ops.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ops changing a driver's vehicle. Send only what changes. reason is required and kept in the account history. */
public record EditVehicleRequest(@Size(max = 50) String make,
                                 @Size(max = 50) String model,
                                 @Min(1980) @Max(2100) Integer year,
                                 @Size(max = 30) String color,
                                 @Size(max = 20) String plateNumber,
                                 @Min(1) @Max(14) Integer seatCapacity,
                                 @NotBlank(message = "is required") @Size(max = 500) String reason) {
}

package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;

/** ok=false is "I need help" - it raises an SOS with this location. */
public record SafetyCheckAnswer(@NotNull Boolean ok, Double latitude, Double longitude) {
}

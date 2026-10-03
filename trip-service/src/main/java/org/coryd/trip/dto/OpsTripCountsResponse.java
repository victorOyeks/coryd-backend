package org.coryd.trip.dto;

public record OpsTripCountsResponse(
        long all,
        long active,
        long completed,
        long cancelled
) {
}

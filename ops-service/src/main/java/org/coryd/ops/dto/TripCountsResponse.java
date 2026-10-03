package org.coryd.ops.dto;

public record TripCountsResponse(
        long all,
        long active,
        long completed,
        long cancelled
) {
}

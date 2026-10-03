package org.coryd.ops.dto;

import java.util.List;

public record LiveTripsResponse(
        int total,
        int active,
        int boarding,
        List<LiveTripItem> trips
) {
}

package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.time.Instant;

/** One SOS in the Ops Console - bookingId is null when a driver raised it on the route itself. */
public record OpsSosItem(
        String id,
        Instant triggeredAt,
        String triggeredBy,
        String triggeredByRole,
        String bookingId,
        String routePostId,
        String driverId,
        String originLabel,
        String destinationLabel,
        RouteStatus routeStatus,
        Double latitude,
        Double longitude,
        Instant opsNotifiedAt
) {
}

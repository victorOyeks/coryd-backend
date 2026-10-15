package org.coryd.ops.dto;

import org.coryd.common.enums.RouteStatus;

import java.time.Instant;

/** One SOS in the Ops Console - who pressed it and how to reach them and the driver, where, and on which trip. */
public record SosListItem(
        String id,
        Instant triggeredAt,
        String triggeredByRole,
        String triggeredBy,
        String triggeredByName,
        String triggeredByPhone,
        String driverId,
        String driverName,
        String driverPhone,
        String bookingId,
        String routePostId,
        String originLabel,
        String destinationLabel,
        RouteStatus routeStatus,
        Double latitude,
        Double longitude,
        Instant opsNotifiedAt
) {
}

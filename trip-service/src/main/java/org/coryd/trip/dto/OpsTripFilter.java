package org.coryd.trip.dto;

import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * The Ops Console trips table's filters - all optional. status is the tab group; routeStatuses narrows to exact route
 * states. from/to are Lagos calendar days of the departure, both inclusive. riderId keeps trips that rider paid for a
 * seat on. search matches either end of the route, the start of the trip id, or a T-/D- reference.
 */
public record OpsTripFilter(
        OpsTripStatus status,
        List<RouteStatus> routeStatuses,
        LocalDate from,
        LocalDate to,
        String driverId,
        String riderId,
        String search
) {
    public static OpsTripFilter of(OpsTripStatus status, String search) {
        return new OpsTripFilter(status, null, null, null, null, null, search);
    }

    public OpsTripFilter withStatus(OpsTripStatus newStatus, List<RouteStatus> newRouteStatuses) {
        return new OpsTripFilter(newStatus, newRouteStatuses, from, to, driverId, riderId, search);
    }
}

package org.coryd.trip.dto;

import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;

import java.time.LocalDate;

/**
 * Cancellations Log filters, all optional. With no kind and no routePostId the log lists each cancellation once: whole
 * trips as ROUTE rows (their seats folded in) and seats cancelled on their own. kind=BOOKING lists every seat, a
 * trip's own included; routePostId lists everything for that trip. from/to are Lagos days of the cancellation.
 */
public record OpsCancellationFilter(
        CancellationSource source,
        CancellationFault fault,
        CancellationLog.Kind kind,
        LocalDate from,
        LocalDate to,
        String driverId,
        String riderId,
        String routePostId,
        String search
) {
    public OpsCancellationFilter withSource(CancellationSource newSource) {
        return new OpsCancellationFilter(newSource, fault, kind, from, to, driverId, riderId, routePostId, search);
    }
}

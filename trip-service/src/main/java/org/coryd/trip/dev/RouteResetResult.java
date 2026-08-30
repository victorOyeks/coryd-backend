package org.coryd.trip.dev;

/** Exactly what the reset touched, so QA can see the route really is back to square one. */
public record RouteResetResult(
        String routePostId,
        String previousStatus,
        int seatsRestored,
        int bookingsDeleted,
        long statusHistoryDeleted,
        long ratingsDeleted,
        long issueReportsDeleted,
        long sosIncidentsDeleted,
        long chatMessagesDeleted,
        String paymentRecordsPurged
) {
}

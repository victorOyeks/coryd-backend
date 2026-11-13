package org.coryd.trip.dto;

/** What the Ops Console trips table can be ordered by - each a real column, sorted in the database before paging. */
public enum OpsTripSort {
    DEPARTURE_TIME("departureTime"),
    POSTED_AT("createdAt"),
    FARE_PER_SEAT("farePerSeat"),
    STATUS("status"),
    STARTED_AT("startedAt"),
    COMPLETED_AT("completedAt");

    private final String property;

    OpsTripSort(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }
}

package org.coryd.trip.dto;

/**
 * One route and time window riders are looking for - totals only, never who. windowStart/windowEnd are
 * the earliest and latest times these riders want to leave, typicalTime the middle one ("07:15", Lagos
 * time). The coordinates are the middle of where riders start and end, ready to pre-fill the
 * post-a-ride form. ridesPosted counts rides already posted for it in the next 7 days.
 */
public record RouteDemandItem(
        String originLabel,
        String destinationLabel,
        double originLat,
        double originLng,
        double destinationLat,
        double destinationLng,
        DemandDayType dayType,
        String windowStart,
        String windowEnd,
        String typicalTime,
        int riderCount,
        int notifyMeCount,
        int ridesPosted,
        DemandRelevance relevance
) {
}

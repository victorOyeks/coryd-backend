package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The Ops Console's pricing monitor for routes departing in [from, to] (Lagos days). A corridor is the normalised
 * "origin->destination" key the suggested-fare job buckets on; suggestedMin/Max is its current suggested range (null when
 * too few fares have been posted on it). An outlier is a route whose fare per seat is outside that range. riderFareReports
 * are riders' PAYMENT-category issue reports on seats of these routes.
 */
public record OpsPricingResponse(LocalDate from, LocalDate to, Totals totals, List<Corridor> corridors, List<Outlier> outliers,
                                 List<RiderReport> riderFareReports) {

    public record Totals(long routes, long corridors, long corridorsWithRange, long outliers, long riderFareReports) {
    }

    public record Corridor(String corridorKey, String originLabel, String destinationLabel, long routes, BigDecimal fareMin,
                           BigDecimal fareMedian, BigDecimal fareAvg, BigDecimal fareMax, BigDecimal suggestedMin,
                           BigDecimal suggestedMax, Integer sampleSize, long outliers, long riderFareReports) {
    }

    public record Outlier(String routePostId, String driverId, String corridorKey, String originLabel, String destinationLabel,
                          Instant departureTime, String status, BigDecimal farePerSeat, BigDecimal suggestedMin,
                          BigDecimal suggestedMax, String direction, BigDecimal deviationPercent) {
    }

    public record RiderReport(String id, String referenceNumber, String bookingId, String routePostId, String riderId,
                              String corridorKey, String description, String status, Instant reportedAt) {
    }
}

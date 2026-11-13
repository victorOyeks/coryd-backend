package org.coryd.ops.dto;

/**
 * The Riders Directory's tab badges and KPI cards - each rider is active or suspended. verified: identity status
 * APPROVED. activeLast7Days: completed a trip in the last 7 x 24 hours.
 */
public record RiderDirectoryCounts(long all, long active, long suspended, long verified, long activeLast7Days) {
}

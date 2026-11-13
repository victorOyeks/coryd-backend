package org.coryd.profile.dto;

/**
 * The Riders Directory's tab badges and KPI cards - each rider is active or suspended, and all of them are under all.
 * verified: identity status APPROVED. activeLast7Days: completed a trip in the last 7 x 24 hours.
 */
public record RiderIndexCounts(long all, long active, long suspended, long verified, long activeLast7Days) {
}

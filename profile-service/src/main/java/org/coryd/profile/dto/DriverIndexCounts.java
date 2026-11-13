package org.coryd.profile.dto;

/**
 * The Drivers Directory's tab badges - each driver under exactly one status - and its KPI cards. activeLast7Days: a
 * completed ride on their trips in the last 7 x 24 hours.
 */
public record DriverIndexCounts(long all, long verified, long pending, long resubmitted, long suspended, long activeLast7Days) {
}

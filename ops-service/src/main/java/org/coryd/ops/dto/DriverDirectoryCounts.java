package org.coryd.ops.dto;

/**
 * The Drivers Directory's tab badges - each driver is counted under exactly one status, and all of them under all - and
 * activeLast7Days for the KPI cards: a completed ride on their trips in the last 7 x 24 hours.
 */
public record DriverDirectoryCounts(long all, long verified, long pending, long resubmitted, long suspended, long activeLast7Days) {
}

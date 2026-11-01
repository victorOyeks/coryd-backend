package org.coryd.ops.dto;

/** The Drivers Directory's tab badges - each driver is counted under exactly one status, and all of them under all. */
public record DriverDirectoryCounts(long all, long verified, long pending, long resubmitted, long suspended) {
}

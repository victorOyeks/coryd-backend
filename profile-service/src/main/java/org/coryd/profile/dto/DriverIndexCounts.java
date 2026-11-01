package org.coryd.profile.dto;

/** The Drivers Directory's tab badges - each driver under exactly one status. */
public record DriverIndexCounts(long all, long verified, long pending, long resubmitted, long suspended) {
}

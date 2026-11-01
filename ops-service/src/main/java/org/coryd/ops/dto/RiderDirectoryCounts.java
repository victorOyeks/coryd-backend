package org.coryd.ops.dto;

/** The Riders Directory's tab badges - each rider is active or suspended. */
public record RiderDirectoryCounts(long all, long active, long suspended) {
}

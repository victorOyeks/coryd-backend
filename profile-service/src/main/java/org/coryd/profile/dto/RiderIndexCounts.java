package org.coryd.profile.dto;

/** The Riders Directory's tab badges - each rider is active or suspended, and all of them are under all. */
public record RiderIndexCounts(long all, long active, long suspended) {
}

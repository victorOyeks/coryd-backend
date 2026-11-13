package org.coryd.ops.dto;

/** A rider as the console shows one - ref is "R-" and the start of the id. */
public record RiderSummary(String id, String ref, String name, String phone, String photoUrl) {
}

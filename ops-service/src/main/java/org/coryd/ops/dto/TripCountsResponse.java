package org.coryd.ops.dto;

/** Trips tab badges: the groups, the finer route states, and how many in-progress trips are stuck. */
public record TripCountsResponse(
        long all,
        long active,
        long completed,
        long cancelled,
        long published,
        long full,
        long inProgress,
        long stuck
) {
}

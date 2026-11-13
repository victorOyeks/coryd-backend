package org.coryd.trip.dto;

/**
 * Ops Console trips tab badges - the groups (active = published, full or in progress) plus the finer route states,
 * and how many in-progress trips are stuck. All follow the same filters as the table, except status.
 */
public record OpsTripCountsResponse(
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
